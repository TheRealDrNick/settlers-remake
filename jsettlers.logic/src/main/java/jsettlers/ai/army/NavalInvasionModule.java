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
import java.util.Comparator;
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
 * enemy shore, unloads the troops and orders them to attack. It handles two situations:
 * <ul>
 * <li><b>pure invasion</b> - an enemy that cannot be reached by land at all (island enemy).</li>
 * <li><b>amphibious flanking</b> - an enemy that <i>is</i> reachable by land, but where a frontal land assault looks unfavourable
 * (we do not clearly outnumber them). Instead of grinding the defended land front, the AI ships a force around it and lands next to the
 * enemy's least defended (ideally unmanned) military building, bypassing the chokepoint.</li>
 * </ul>
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
	// upper bound on how many ferries the AI will build for one invasion, to cap the material/economy investment
	private static final int MAX_FERRIES = 3;
	// base number of surplus soldiers (beyond the home garrison) required before an invasion is started - scaled by difficulty below
	private static final int BASE_MIN_INVASION_FORCE = 7;
	// base number of soldiers kept at home to defend - scaled by difficulty below
	private static final int BASE_HOME_GARRISON_RESERVE = 10;
	// distance below which a ferry is considered to have "arrived" at its target water position
	private static final int FERRY_ARRIVAL_DISTANCE = 6;
	// how far around an enemy building to look for a navigable landing water tile
	private static final int LANDING_SEARCH_RADIUS = 20;
	// a frontal land assault is considered unfavourable (and flanking preferred) unless we have at least this many times the enemy's soldiers
	private static final float LAND_ASSAULT_REQUIRED_EDGE = 1.25f;
	// added to a flank candidate's defense score when its tower is occupied, so unmanned (free to conquer) towers are strongly preferred
	private static final int OCCUPIED_TOWER_PENALTY = 100;
	// enemy soldiers within this distance of a building count as defending it (used for flank target selection)
	private static final int FLANK_DEFENSE_RADIUS = 10;
	// our soldiers within this distance of the landing tile (and on its landmass) are treated as the landed expeditionary force
	private static final int EXPEDITION_COMMAND_RADIUS = 15;

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

		InvasionTarget invasion = selectInvasionTarget(dockWater);
		if (invasion == null) {
			return;
		}
		IPlayer target = invasion.enemy;
		ShortPoint2D landingTile = invasion.landingTile;

		List<IFerryMovable> ferries = findFriendlyFerries();
		List<ShortPoint2D> idleHomeSoldiers = collectIdleHomeSoldiers(soldiersWithOrders);

		// build up a small fleet sized to the force we want to ship, so invasions arrive as meaningful waves rather than a trickle of
		// one or two soldiers. The dockyard builds one ship at a time and ignores extra orders, so we can request every tick.
		boolean invasionWorthwhile = idleHomeSoldiers.size() >= homeGarrisonReserve + minInvasionForce;
		if (invasionWorthwhile) {
			int surplus = idleHomeSoldiers.size() - homeGarrisonReserve;
			int desiredFerries = Math.min(MAX_FERRIES, Math.max(1, (int) Math.ceil(surplus / (float) FERRY_CAPACITY)));
			if (ferries.size() < desiredFerries) {
				parent.taskScheduler.scheduleTask(new OrderShipGuiTask(playerId, dockyard, EShipType.FERRY));
			}
		}

		for (IFerryMovable ferry : ferries) {
			// note: we must NOT pre-add the ferry id to soldiersWithOrders here - sendTroopsToById() strips ids that are already in
			// that set, which would cancel the ferry's own move order. Ferries are never commanded by the other army modules anyway.
			ShortPoint2D ferryPosition = ferry.getPosition();
			int passengerCount = ferry.getPassengers().size();
			boolean atHome = ferryPosition.getOnGridDistTo(dockWater) <= FERRY_ARRIVAL_DISTANCE;
			int remainingSurplus = idleHomeSoldiers.size() - homeGarrisonReserve;

			if (atHome && passengerCount < FERRY_CAPACITY && remainingSurplus > 0) {
				loadSoldiers(ferry, idleHomeSoldiers, soldiersWithOrders); // keep topping up until full or no surplus troops remain
			} else if (passengerCount == 0) {
				sendFerryTo(ferry, dockWater, soldiersWithOrders); // empty and nothing to load here -> return home for troops
			} else if (ferryPosition.getOnGridDistTo(landingTile) <= FERRY_ARRIVAL_DISTANCE) {
				parent.taskScheduler.scheduleTask(new MovableGuiTask(EGuiAction.UNLOAD_FERRY, playerId, listOf(ferry.getID())));
			} else {
				sendFerryTo(ferry, landingTile, soldiersWithOrders); // full (or no more troops to wait for) -> sail to the enemy shore
			}
		}

		commandExpeditionaryForce(target, landingTile, soldiersWithOrders);
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

	/**
	 * Orders the troops that have been ferried across and unloaded near {@code landingTile} to attack the enemy. The expeditionary force
	 * is identified by proximity to the landing tile (and being on the same landmass as it), which works both for island invasions - where
	 * the troops are on a different landmass than our base - and for same-landmass flanking, where a partition test could not tell them
	 * apart from our home army.
	 */
	private void commandExpeditionaryForce(IPlayer target, ShortPoint2D landingTile, Set<Integer> soldiersWithOrders) {
		List<ShortPoint2D> landed = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(playerId, EMovableType.SOLDIERS)) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable == null || soldiersWithOrders.contains(movable.getID())) {
				continue;
			}
			if (position.getOnGridDistTo(landingTile) <= EXPEDITION_COMMAND_RADIUS
					&& landscapeGrid.isReachable(landingTile.x, landingTile.y, position.x, position.y, false)) {
				landed.add(position); // this soldier has been ferried over and is now ashore near the landing site
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
		return findNavigableLandingNear(focus, dockWater);
	}

	/** @return the nearest water tile to {@code target} that is navigable by ship from {@code dockWater}, or null if none is in range. */
	private ShortPoint2D findNavigableLandingNear(ShortPoint2D target, ShortPoint2D dockWater) {
		// unloading later drops troops on the land next to this water tile (see FerryMovable.unloadFerry)
		Optional<ShortPoint2D> landing = HexGridArea.stream(target.x, target.y, 1, LANDING_SEARCH_RADIUS)
				.filterBounds(width, height)
				.filter((x, y) -> landscapeGrid.getLandscapeTypeAt(x, y).isWater)
				.filter((x, y) -> landscapeGrid.isReachable(dockWater.x, dockWater.y, x, y, true))
				.getFirst();
		return landing.orElse(null);
	}

	/**
	 * Chooses which enemy to attack by sea and where to land, preferring pure island invasions over amphibious flanks.
	 */
	private InvasionTarget selectInvasionTarget(ShortPoint2D dockWater) {
		List<IPlayer> flankingCandidates = new ArrayList<>();
		for (IPlayer enemy : parent.aiStatistics.getAliveEnemiesOf(parent.getPlayer())) {
			if (parent.hasLandReachableMilitaryBuilding(enemy)) {
				flankingCandidates.add(enemy); // reachable by land: only a candidate for flanking
				continue;
			}
			ShortPoint2D landing = findLandingTile(enemy, dockWater);
			if (landing != null) {
				return new InvasionTarget(enemy, landing); // island enemy: pure invasion takes priority
			}
		}
		for (IPlayer enemy : flankingCandidates) {
			if (!landAssaultUnfavorable(enemy)) {
				continue; // we clearly outnumber them - let the normal land attack strategy walk in
			}
			ShortPoint2D landing = findFlankingLandingTile(enemy, dockWater);
			if (landing != null) {
				return new InvasionTarget(enemy, landing);
			}
		}
		return null;
	}

	private boolean landAssaultUnfavorable(IPlayer enemy) {
		int ourSoldiers = parent.aiStatistics.getCountOfMovablesOfPlayer(parent.getPlayer(), EMovableType.SOLDIERS);
		int enemySoldiers = parent.aiStatistics.getCountOfMovablesOfPlayer(enemy, EMovableType.SOLDIERS);
		// unfavourable unless we clearly outnumber the enemy; then bypassing the defended land front by sea is the better option
		return ourSoldiers < enemySoldiers * LAND_ASSAULT_REQUIRED_EDGE;
	}

	/**
	 * For a land-reachable enemy, picks a landing next to its least defended military building - preferring unmanned towers - so the
	 * expedition lands behind the defended land front and can conquer a lightly held building to gain a foothold.
	 */
	private ShortPoint2D findFlankingLandingTile(IPlayer enemy, ShortPoint2D dockWater) {
		List<ShortPoint2D> militaryBuildings = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, enemy.getPlayerId())) {
			Building building = parent.aiStatistics.getBuildingAt(position);
			if (building != null && building.isConstructionFinished()) {
				militaryBuildings.add(position);
			}
		}
		List<ShortPoint2D> enemySoldiers = parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(enemy.getPlayerId(), EMovableType.SOLDIERS);
		militaryBuildings.sort(Comparator.comparingInt(pos -> flankDefenseScore(pos, enemySoldiers)));
		for (ShortPoint2D building : militaryBuildings) {
			ShortPoint2D landing = findNavigableLandingNear(building, dockWater);
			if (landing != null) {
				return landing;
			}
		}
		return null;
	}

	private int flankDefenseScore(ShortPoint2D buildingPosition, List<ShortPoint2D> enemySoldiers) {
		Building building = parent.aiStatistics.getBuildingAt(buildingPosition);
		int score = (building != null && building.isOccupied()) ? OCCUPIED_TOWER_PENALTY : 0;
		for (ShortPoint2D soldier : enemySoldiers) {
			if (soldier.getOnGridDistTo(buildingPosition) <= FLANK_DEFENSE_RADIUS) {
				score++;
			}
		}
		return score;
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

	/** The enemy to attack by sea together with the water tile the ferry should sail to and unload at. */
	private static final class InvasionTarget {
		final IPlayer enemy;
		final ShortPoint2D landingTile;

		InvasionTarget(IPlayer enemy, ShortPoint2D landingTile) {
			this.enemy = enemy;
			this.landingTile = landingTile;
		}
	}
}
