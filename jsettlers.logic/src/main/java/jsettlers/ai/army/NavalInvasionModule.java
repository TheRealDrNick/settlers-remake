/*******************************************************************************
 * Copyright (c) 2024
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package jsettlers.ai.army;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jsettlers.ai.highlevel.AiStatistics;
import jsettlers.common.action.EMoveToType;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.map.shapes.HexGridArea;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.movable.EShipType;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.input.tasks.EGuiAction;
import jsettlers.input.tasks.MovableGuiTask;
import jsettlers.input.tasks.OrderShipGuiTask;
import jsettlers.logic.DockPosition;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.buildings.workers.DockyardBuilding;
import jsettlers.logic.map.grid.MainGrid;
import jsettlers.logic.map.grid.landscape.LandscapeGrid;
import jsettlers.logic.map.grid.movable.MovableGrid;
import jsettlers.logic.movable.interfaces.IFerryMovable;
import jsettlers.logic.movable.interfaces.ILogicMovable;

/**
 * Enables the AI to attack enemies that are separated from it by water. Once the economy has provided a dockyard with a dock (see
 * {@link jsettlers.ai.highlevel.WhatToDoAi#assignDocks()}), this module builds ferries, loads surplus soldiers onto them, sails them to an
 * enemy shore, unloads the troops and orders them to attack.
 * <p>
 * The module is a stateless, world-observing controller: every heavy tick it re-derives the current situation from the game state (which
 * ferries exist, whether they are loaded, where they are, which soldiers have already landed) and issues the next appropriate order. It
 * keeps no serialized state of its own, which makes it robust across save/load, and it uses no randomness, keeping multiplayer/replays in
 * sync.
 *
 * @author jsettlers naval AI
 */
public class NavalInvasionModule extends ArmyModule {

	private static final int FERRY_CAPACITY = 7;
	// base number of surplus soldiers (beyond the home garrison) required before an invasion is started - scaled by difficulty below
	private static final int BASE_MIN_INVASION_FORCE = 7;
	// base number of soldiers kept at home to defend - scaled by difficulty below
	private static final int BASE_HOME_GARRISON_RESERVE = 10;
	// distance below which a ferry is considered to have "arrived" at its target water position
	private static final int FERRY_ARRIVAL_DISTANCE = 6;
	// how far around an enemy building to look for a navigable landing water tile
	private static final int LANDING_SEARCH_RADIUS = 20;

	// how eagerly each difficulty invades across water: a higher factor means a bigger required surplus and garrison, i.e. more caution.
	// indexed by EPlayerType.ordinal(): AI_VERY_EASY, AI_EASY, AI_HARD, AI_VERY_HARD, HUMAN
	private static final float[] INVASION_CAUTION_BY_PLAYER_TYPE = { 2.0F, 1.5F, 1.2F, 1.0F, 1.0F };

	private final LandscapeGrid landscapeGrid;
	private final short width;
	private final short height;
	private final byte playerId;
	private final int minInvasionForce;
	private final int homeGarrisonReserve;

	public NavalInvasionModule(ArmyFramework parent) {
		super(parent);
		MainGrid mainGrid = parent.aiStatistics.getMainGrid();
		this.landscapeGrid = mainGrid.getLandscapeGrid();
		this.width = mainGrid.getWidth();
		this.height = mainGrid.getHeight();
		this.playerId = parent.getPlayerId();

		float caution = INVASION_CAUTION_BY_PLAYER_TYPE[parent.getPlayer().getPlayerType().ordinal()];
		this.minInvasionForce = Math.round(BASE_MIN_INVASION_FORCE * caution);
		this.homeGarrisonReserve = Math.round(BASE_HOME_GARRISON_RESERVE * caution);
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
		DockyardBuilding dockyard = findReadyDockyard();
		if (dockyard == null || dockyard.getDock() == null) {
			return; // no usable dockyard yet - the economy still has to build the infrastructure
		}
		ShortPoint2D dockWater = dockyard.getDock().getWaterPosition();

		// pick an enemy that we cannot reach by land but whose shore we can reach by sea
		IPlayer target = null;
		ShortPoint2D landingTile = null;
		for (IPlayer enemy : parent.aiStatistics.getAliveEnemiesOf(parent.getPlayer())) {
			if (parent.hasLandReachableMilitaryBuilding(enemy)) {
				continue; // reachable by land, handled by the normal attack strategy
			}
			ShortPoint2D candidate = findLandingTile(enemy, dockWater);
			if (candidate != null) {
				target = enemy;
				landingTile = candidate;
				break;
			}
		}
		if (target == null) {
			return;
		}

		List<IFerryMovable> ferries = findFriendlyFerries();
		List<ShortPoint2D> idleHomeSoldiers = collectIdleHomeSoldiers(soldiersWithOrders);

		if (ferries.isEmpty()) {
			// only start an invasion once we have enough surplus troops to make it worthwhile
			if (idleHomeSoldiers.size() >= homeGarrisonReserve + minInvasionForce) {
				parent.taskScheduler.scheduleTask(new OrderShipGuiTask(playerId, dockyard, EShipType.FERRY));
			}
			commandLandedSoldiers(target, soldiersWithOrders);
			return;
		}

		for (IFerryMovable ferry : ferries) {
			// note: we must NOT pre-add the ferry id to soldiersWithOrders here - sendTroopsToById() strips ids that are already in
			// that set, which would cancel the ferry's own move order. Ferries are never commanded by the other army modules anyway.
			ShortPoint2D ferryPosition = ferry.getPosition();
			int passengerCount = ferry.getPassengers().size();

			if (passengerCount == 0) {
				if (ferryPosition.getOnGridDistTo(dockWater) <= FERRY_ARRIVAL_DISTANCE) {
					loadSoldiers(ferry, idleHomeSoldiers, soldiersWithOrders);
				} else {
					sendFerryTo(ferry, dockWater, soldiersWithOrders); // go home to pick up troops
				}
			} else {
				if (ferryPosition.getOnGridDistTo(landingTile) <= FERRY_ARRIVAL_DISTANCE) {
					parent.taskScheduler.scheduleTask(new MovableGuiTask(EGuiAction.UNLOAD_FERRY, playerId, listOf(ferry.getID())));
				} else {
					sendFerryTo(ferry, landingTile, soldiersWithOrders); // sail the loaded ferry to the enemy shore
				}
			}
		}

		commandLandedSoldiers(target, soldiersWithOrders);
	}

	private void loadSoldiers(IFerryMovable ferry, List<ShortPoint2D> idleHomeSoldiers, Set<Integer> soldiersWithOrders) {
		int freeSpace = FERRY_CAPACITY - ferry.getPassengers().size();
		int surplus = idleHomeSoldiers.size() - homeGarrisonReserve;
		if (freeSpace <= 0 || surplus <= 0) {
			return;
		}
		int toLoad = Math.min(freeSpace, surplus);
		List<ShortPoint2D> batch = new ArrayList<>(idleHomeSoldiers.subList(0, toLoad));
		idleHomeSoldiers.subList(0, toLoad).clear(); // consume them so a second ferry in the same tick loads different soldiers
		// sending soldiers onto a water tile that holds a friendly ferry makes them board it (see GuiTaskExecutor.moveSelectedTo)
		parent.sendTroopsTo(batch, ferry.getPosition(), soldiersWithOrders, EMoveToType.DEFAULT);
	}

	private void commandLandedSoldiers(IPlayer target, Set<Integer> soldiersWithOrders) {
		List<ShortPoint2D> landed = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(playerId, EMovableType.SOLDIERS)) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable == null || soldiersWithOrders.contains(movable.getID())) {
				continue;
			}
			if (!parent.isReachableByLand(position)) {
				landed.add(position); // a soldier not on our landmass has been ferried onto the enemy island
			}
		}
		if (landed.isEmpty()) {
			return;
		}
		ShortPoint2D groupCenter = AiStatistics.calculateAveragePointFromList(landed);
		ShortPoint2D targetDoor = nearestReachableEnemyDoor(target, groupCenter);
		if (targetDoor != null) {
			parent.sendTroopsTo(landed, targetDoor, soldiersWithOrders, EMoveToType.DEFAULT);
		}
	}

	private ShortPoint2D nearestReachableEnemyDoor(IPlayer enemy, ShortPoint2D from) {
		ShortPoint2D best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, enemy.getPlayerId())) {
			Building building = parent.aiStatistics.getBuildingAt(position);
			if (building == null || !building.isConstructionFinished()) {
				continue;
			}
			ShortPoint2D door = building.getDoor();
			if (!landscapeGrid.isReachable(from.x, from.y, door.x, door.y, false)) {
				continue; // only attack buildings the landed troops can actually walk to
			}
			int distance = from.getOnGridDistTo(door);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = door;
			}
		}
		return best;
	}

	private ShortPoint2D findLandingTile(IPlayer enemy, ShortPoint2D dockWater) {
		List<ShortPoint2D> enemyBuildings = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, enemy.getPlayerId())) {
			Building building = parent.aiStatistics.getBuildingAt(position);
			if (building != null && building.isConstructionFinished()) {
				enemyBuildings.add(position);
			}
		}
		ShortPoint2D focus = AiStatistics.detectNearestPointFromList(dockWater, enemyBuildings);
		if (focus == null) {
			return null;
		}
		// nearest water tile to the enemy building that is navigable from our dock; unloading later drops troops on the adjacent land
		Optional<ShortPoint2D> landing = HexGridArea.stream(focus.x, focus.y, 1, LANDING_SEARCH_RADIUS)
				.filterBounds(width, height)
				.filter((x, y) -> landscapeGrid.getLandscapeTypeAt(x, y).isWater)
				.filter((x, y) -> landscapeGrid.isReachable(dockWater.x, dockWater.y, x, y, true))
				.getFirst();
		return landing.orElse(null);
	}

	private List<ShortPoint2D> collectIdleHomeSoldiers(Set<Integer> soldiersWithOrders) {
		List<ShortPoint2D> result = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(playerId, EMovableType.SOLDIERS)) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable == null || soldiersWithOrders.contains(movable.getID())) {
				continue;
			}
			if (parent.isReachableByLand(position)) {
				result.add(position);
			}
		}
		return result;
	}

	private List<IFerryMovable> findFriendlyFerries() {
		List<IFerryMovable> ferries = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, EMovableType.FERRY)) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable instanceof IFerryMovable) {
				ferries.add((IFerryMovable) movable);
			}
		}
		return ferries;
	}

	private DockyardBuilding findReadyDockyard() {
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypeForPlayer(EBuildingType.DOCKYARD, playerId)) {
			Building building = parent.aiStatistics.getBuildingAt(position);
			if (building instanceof DockyardBuilding && building.isConstructionFinished() && building.isOccupied()) {
				return (DockyardBuilding) building;
			}
		}
		return null;
	}

	private void sendFerryTo(IFerryMovable ferry, ShortPoint2D target, Set<Integer> soldiersWithOrders) {
		parent.sendTroopsToById(listOf(ferry.getID()), target, soldiersWithOrders, EMoveToType.DEFAULT);
	}

	private static List<Integer> listOf(int id) {
		List<Integer> list = new ArrayList<>(1);
		list.add(id);
		return list;
	}
}
