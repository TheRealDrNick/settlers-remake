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
import java.util.Set;

import jsettlers.algorithms.construction.AbstractConstructionMarkableMap;
import jsettlers.common.action.EMoveToType;
import jsettlers.common.action.SetTradingWaypointAction.EWaypointType;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.map.partition.IPartitionData;
import jsettlers.common.material.EMaterialType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.movable.EShipType;
import jsettlers.common.player.ECivilisation;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.input.tasks.ChangeTradingRequestGuiTask;
import jsettlers.input.tasks.ConstructBuildingTask;
import jsettlers.input.tasks.ConvertGuiTask;
import jsettlers.input.tasks.EGuiAction;
import jsettlers.input.tasks.MovableGuiTask;
import jsettlers.input.tasks.OrderShipGuiTask;
import jsettlers.input.tasks.SetDockGuiTask;
import jsettlers.input.tasks.SetTradingWaypointGuiTask;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.buildings.IDockBuilding;
import jsettlers.logic.buildings.trading.HarborBuilding;
import jsettlers.logic.buildings.workers.DockyardBuilding;
import jsettlers.logic.map.grid.MainGrid;
import jsettlers.logic.map.grid.landscape.LandscapeGrid;
import jsettlers.logic.map.grid.partition.PartitionsGrid;
import jsettlers.logic.movable.interfaces.IFerryMovable;
import jsettlers.logic.movable.interfaces.ILogicMovable;

/**
 * Phase 2 of the cross-water colonization feature: the AI tries to <b>build and hold</b> the beachhead that {@link ColonizationModule} claimed
 * in Phase 1. A claimed-but-unenforced beachhead can be re-taken (see {@code AiStatistics.isIngestibleByPioneersOf}, which requires
 * {@code !isEnforcedByTower}); only an occupied military building enforces the ground, so this module works towards raising and manning a
 * tower on the foreign landmass.
 * <p>
 * The engine makes this hard: material delivery, worker creation and bearers are all <b>partition-local</b>. A freshly claimed beachhead
 * partition has its own {@code PartitionManager} with no stock, no offers and no carriers, and the only cross-water transport in the game
 * (the ferry) carries {@code IAttackableHumanMovable}s (pioneers/soldiers) - never bearers and never goods. This module therefore assembles
 * the missing pieces on the beachhead:
 * <ol>
 * <li><b>Carrier bootstrap</b> - it converts a few idle pioneers that are already ashore into {@code BEARER}s. A bearer registers as jobless
 *     in the partition it is standing on, so this is the one way to give the beachhead partition local carriers without a living house.</li>
 * <li><b>Material delivery via sea trade</b> - it raises a {@code HARBOR} with a dock at the home coast, orders a {@code CARGO_SHIP} from the
 *     existing dockyard, points the harbor's trade route at a beachhead coast tile and requests the goods an outpost needs
 *     ({@code PLANK} + {@code STONE} to build, plus a {@code HAMMER} for a bricklayer and a {@code BLADE} for a digger). The cargo ship drops
 *     these on the beachhead coast, where {@code MainGrid.dropMaterial} turns them into offers in the beachhead partition.</li>
 * <li><b>Tower + garrison</b> - once construction goods have actually arrived in the beachhead partition it places a {@code TOWER} on a
 *     buildable beachhead tile, and once that tower is finished but unmanned it ships a soldier across to occupy it.</li>
 * </ol>
 * <p>
 * Like {@link ColonizationModule} and {@link NavalInvasionModule} the module is <b>stateless and deterministic</b>: every heavy tick it
 * re-derives the whole situation from the game state and issues the next order, so it is robust across save/load and stays in sync in
 * multiplayer (it uses no randomness).
 * <h4>Inertness</h4>
 * The module returns immediately at the difficulty gate for easy AIs / the human, and at the beachhead gate whenever the player owns no
 * buildable ground across water - which is always the case on land-only maps such as the difficulty-test map {@code SpezialSumpf}. On those
 * maps it issues <b>zero</b> {@code GuiTask}s, so the difficulty ladder is unaffected.
 *
 * @author jsettlers colonization AI
 */
public class ColonizationBuildModule extends ArmyModule {

	// which difficulties colonize at all: only the two strong AIs (mirrors ColonizationModule). Indexed by EPlayerType.ordinal():
	// AI_VERY_EASY, AI_EASY, AI_HARD, AI_VERY_HARD, HUMAN.
	private static final boolean[] COLONIZATION_ENABLED_BY_PLAYER_TYPE = { false, false, true, true, false };

	// goods shipped to the beachhead so an outpost can be raised: PLANK + STONE build the tower, a HAMMER lets a bearer become a bricklayer
	// and a BLADE lets one become a digger (see EMovableType.BRICKLAYER/DIGGER tool requirements).
	private static final EMaterialType[] OUTPOST_TRADE_MATERIALS = { EMaterialType.PLANK, EMaterialType.STONE, EMaterialType.HAMMER, EMaterialType.BLADE };
	// target amount requested per material at the harbor; topped up each heavy tick as the cargo ship consumes it
	private static final int TRADE_REQUEST_PER_MATERIAL = 8;
	// idle beachhead pioneers converted to bearers so the beachhead partition has local carriers to serve the construction site
	private static final int MIN_BEACHHEAD_BEARERS = 4;
	// enough construction goods must have arrived in the beachhead partition before we commit a tower there (so we never litter a dead site)
	private static final int MIN_DELIVERED_TO_BUILD = 1;
	// ferry handling (mirrors ColonizationModule / NavalInvasionModule)
	private static final int FERRY_CAPACITY = 7;
	private static final int FERRY_ARRIVAL_DISTANCE = 6;
	// landed soldiers within this distance of the beachhead tower (and on its landmass) are treated as the garrison expedition
	private static final int GARRISON_COMMAND_RADIUS = 15;

	private final MainGrid mainGrid;
	private final LandscapeGrid landscapeGrid;
	private final PartitionsGrid partitionsGrid;
	private final byte playerId;
	private final boolean colonizationEnabled;

	public ColonizationBuildModule(ArmyFramework parent) {
		super(parent);
		this.mainGrid = parent.aiStatistics.getMainGrid();
		this.landscapeGrid = mainGrid.getLandscapeGrid();
		this.partitionsGrid = mainGrid.getPartitionsGrid();
		this.playerId = parent.getPlayerId();
		this.colonizationEnabled = COLONIZATION_ENABLED_BY_PLAYER_TYPE[parent.getPlayer().getPlayerType().ordinal()];
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
		if (!colonizationEnabled) {
			return; // easy AIs and the human player never colonize across water
		}
		// gate - the player must own buildable ground on a landmass it cannot reach by land, i.e. a claimed beachhead. On maps without one
		// (all land-only maps, e.g. SpezialSumpf) this is null every tick, so the module is completely inert below this point.
		ShortPoint2D beachhead = findBeachheadLand();
		if (beachhead == null) {
			return;
		}

		bootstrapBeachheadCarriers(beachhead);
		driveMaterialDelivery(beachhead);
		buildAndOccupyTower(beachhead, soldiersWithOrders);
	}

	/**
	 * @return a representative buildable tile the player owns on a beachhead landmass (not reachable by land from the base), or null if the
	 *         player owns no such ground. Deterministic: {@code getLandForPlayer} iterates in a stable order and the first match is taken.
	 */
	private ShortPoint2D findBeachheadLand() {
		for (ShortPoint2D land : parent.aiStatistics.getLandForPlayer(playerId)) {
			if (!parent.isReachableByLand(land)) {
				return land;
			}
		}
		return null;
	}

	/**
	 * Converts up to {@link #MIN_BEACHHEAD_BEARERS} idle pioneers that are ashore on the beachhead into bearers, so the beachhead partition
	 * gains local carriers. Without a carrier standing in that partition no shipped goods can ever be delivered to a construction site.
	 */
	private void bootstrapBeachheadCarriers(ShortPoint2D beachhead) {
		int existingBearers = countBeachheadMovables(beachhead, EMovableType.BEARER);
		int wanted = MIN_BEACHHEAD_BEARERS - existingBearers;
		if (wanted <= 0) {
			return;
		}
		List<Integer> convert = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, EMovableType.PIONEER)) {
			if (parent.isReachableByLand(position)) {
				continue; // still on our home landmass - leave it to the home pioneer pool / Phase 1 expedition
			}
			if (!onSameLandmass(position, beachhead)) {
				continue; // ashore on a different foreign landmass than the one we are developing
			}
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable != null) {
				convert.add(movable.getID());
				if (convert.size() >= wanted) {
					break;
				}
			}
		}
		if (!convert.isEmpty()) {
			parent.taskScheduler.scheduleTask(new ConvertGuiTask(playerId, convert, EMovableType.BEARER));
		}
	}

	/**
	 * Drives the sea-trade supply line that feeds the beachhead: build + dock a harbor at the home coast, order a cargo ship, point the trade
	 * route at the beachhead and keep the outpost goods requested. Each step is guarded and re-derived every tick, so partial progress is
	 * safe to repeat.
	 */
	private void driveMaterialDelivery(ShortPoint2D beachhead) {
		DockyardBuilding dockyard = findReadyDockyard();
		if (dockyard == null || dockyard.getDock() == null) {
			return; // cargo ships are built by the dockyard; without a working one there is no way to ship goods
		}

		HarborBuilding harbor = findHarbor();
		if (harbor == null) {
			// no harbor yet - place one at the HOME coast (never on the beachhead, which is now also buildable land): a home-landmass tile
			// that a harbor fits on and that has water within dock range, nearest to the existing dockyard.
			ShortPoint2D harborPosition = findHomeHarborPosition(dockyard.getPosition());
			if (harborPosition != null) {
				parent.taskScheduler.scheduleTask(new ConstructBuildingTask(EGuiAction.BUILD, playerId, harborPosition, EBuildingType.HARBOR));
			}
			return;
		}
		if (!harbor.isConstructionFinished()) {
			return; // still being built at home
		}
		if (harbor.getDock() == null) {
			ShortPoint2D dockWater = findDockWaterPosition(harbor);
			if (dockWater != null) {
				parent.taskScheduler.scheduleTask(new SetDockGuiTask(playerId, harbor, dockWater));
			}
			return;
		}

		// harbor ready: aim its trade route at the beachhead and keep the outpost goods requested. Setting the destination waypoint is
		// idempotent (the harbor snaps it to the nearest coast-reachable tile), so we only (re)set it while none is present.
		if (!harbor.getWaypointsIterator().hasNext()) {
			parent.taskScheduler.scheduleTask(new SetTradingWaypointGuiTask(EGuiAction.SET_TRADING_WAYPOINT, playerId, harbor.getPosition(),
					EWaypointType.DESTINATION, beachhead));
		}
		for (EMaterialType material : OUTPOST_TRADE_MATERIALS) {
			if (harbor.getRequestedTradingFor(material) < TRADE_REQUEST_PER_MATERIAL) {
				parent.taskScheduler.scheduleTask(new ChangeTradingRequestGuiTask(EGuiAction.CHANGE_TRADING, playerId, harbor.getPosition(),
						material, TRADE_REQUEST_PER_MATERIAL, false));
			}
		}

		if (parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, EMovableType.CARGO_SHIP).isEmpty()) {
			parent.taskScheduler.scheduleTask(new OrderShipGuiTask(playerId, dockyard, EShipType.CARGO_SHIP));
		}
	}

	/**
	 * Places a tower on the beachhead once construction goods have arrived there, and ships a soldier to occupy a finished but unmanned
	 * beachhead tower. Occupation is what actually enforces the claimed ground.
	 */
	private void buildAndOccupyTower(ShortPoint2D beachhead, Set<Integer> soldiersWithOrders) {
		Building beachheadTower = findBeachheadMilitaryBuilding(beachhead);
		if (beachheadTower != null) {
			if (beachheadTower.isConstructionFinished() && !beachheadTower.isOccupied()) {
				shipGarrisonSoldier(beachheadTower, beachhead, soldiersWithOrders);
			}
			return; // a tower already exists (finished or still building) - do not place a second one
		}

		if (!hasDeliveredConstructionGoods(beachhead)) {
			return; // wait until the sea-trade supply line has actually landed building material on the beachhead
		}
		ShortPoint2D towerPosition = findBeachheadTowerPosition(beachhead);
		if (towerPosition != null) {
			parent.taskScheduler.scheduleTask(new ConstructBuildingTask(EGuiAction.BUILD, playerId, towerPosition, EBuildingType.TOWER));
		}
	}

	/** @return the player's tower/castle on the beachhead landmass (finished or under construction), or null if none exists yet. */
	private Building findBeachheadMilitaryBuilding(ShortPoint2D beachhead) {
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, playerId)) {
			if (onSameLandmass(position, beachhead)) {
				return parent.aiStatistics.getBuildingAt(position);
			}
		}
		return null;
	}

	/** @return a buildable beachhead tile a tower fits on, or null if none is currently constructable. */
	private ShortPoint2D findBeachheadTowerPosition(ShortPoint2D beachhead) {
		AbstractConstructionMarkableMap constructionGrid = mainGrid.getConstructionMarksGrid();
		for (ShortPoint2D land : parent.aiStatistics.getLandForPlayer(playerId)) {
			if (parent.isReachableByLand(land) || !onSameLandmass(land, beachhead)) {
				continue; // only beachhead ground on the landmass we are developing
			}
			if (constructionGrid.canConstructAt(land.x, land.y, EBuildingType.TOWER, playerId)) {
				return land;
			}
		}
		return null;
	}

	/** @return true once the beachhead partition holds construction goods (planks/stone), i.e. the supply line has begun to deliver. */
	private boolean hasDeliveredConstructionGoods(ShortPoint2D beachhead) {
		IPartitionData data = partitionsGrid.getPartitionDataForManagerAt(beachhead.x, beachhead.y);
		if (data == null) {
			return false;
		}
		return data.getAmountOf(EMaterialType.PLANK) >= MIN_DELIVERED_TO_BUILD
				&& data.getAmountOf(EMaterialType.STONE) >= MIN_DELIVERED_TO_BUILD;
	}

	/**
	 * Ships a single idle home soldier across to occupy the finished beachhead tower, reusing the ferry idiom: load the soldier onto a free
	 * ferry at home, sail to a landing beside the tower, unload, then walk any landed soldier to the tower's door so the tower mans itself.
	 */
	private void shipGarrisonSoldier(Building tower, ShortPoint2D beachhead, Set<Integer> soldiersWithOrders) {
		// step A - a soldier already ashore near the tower: walk it to the door (OccupyingBuilding pulls it in once it is close enough)
		List<ShortPoint2D> landed = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(playerId, EMovableType.SOLDIERS)) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable == null || soldiersWithOrders.contains(movable.getID())) {
				continue;
			}
			if (!parent.isReachableByLand(position) && onSameLandmass(position, beachhead)
					&& position.getOnGridDistTo(tower.getDoor()) <= GARRISON_COMMAND_RADIUS) {
				landed.add(position);
			}
		}
		if (!landed.isEmpty()) {
			parent.sendTroopsTo(landed, tower.getDoor(), soldiersWithOrders, EMoveToType.DEFAULT);
			return;
		}

		// step B - otherwise ferry one over. Reuse a ferry that is idle at home (Phase 1's expedition owns ferries carrying pioneers; leave
		// those alone) and drop the soldier at a landing beside the tower.
		DockyardBuilding dockyard = findReadyDockyard();
		if (dockyard == null || dockyard.getDock() == null) {
			return;
		}
		ShortPoint2D dockWater = dockyard.getDock().getWaterPosition();
		IFerryMovable ferry = findGarrisonFerry(dockWater);
		if (ferry == null) {
			return;
		}
		ShortPoint2D landing = parent.aiStatistics.getSeaReachableLandingNear(playerId, tower.getPosition());
		if (landing == null) {
			return;
		}
		ShortPoint2D ferryPosition = ferry.getPosition();
		boolean atHome = ferryPosition.getOnGridDistTo(dockWater) <= FERRY_ARRIVAL_DISTANCE;
		int passengers = ferry.getPassengers().size();
		if (atHome && passengers == 0) {
			ShortPoint2D soldier = nearestIdleHomeSoldier(dockWater, soldiersWithOrders);
			if (soldier != null) {
				parent.sendTroopsTo(listOfPositions(soldier), ferry.getPosition(), soldiersWithOrders, EMoveToType.DEFAULT); // board
			}
		} else if (passengers > 0) {
			if (ferryPosition.getOnGridDistTo(landing) <= FERRY_ARRIVAL_DISTANCE) {
				parent.taskScheduler.scheduleTask(new MovableGuiTask(EGuiAction.UNLOAD_FERRY, playerId, listOfIds(ferry.getID())));
			} else {
				parent.sendTroopsToById(listOfIds(ferry.getID()), landing, soldiersWithOrders, EMoveToType.DEFAULT);
			}
		}
	}

	private ShortPoint2D nearestIdleHomeSoldier(ShortPoint2D dockWater, Set<Integer> soldiersWithOrders) {
		ShortPoint2D best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(playerId, EMovableType.SOLDIERS)) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable == null || soldiersWithOrders.contains(movable.getID()) || !parent.isReachableByLand(position)) {
				continue;
			}
			int distance = position.getOnGridDistTo(dockWater);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = position;
			}
		}
		return best;
	}

	/** @return an empty ferry waiting at home that is not carrying passengers (so it is not part of the Phase 1 pioneer expedition). */
	private IFerryMovable findGarrisonFerry(ShortPoint2D dockWater) {
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, EMovableType.FERRY)) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable instanceof IFerryMovable) {
				IFerryMovable ferry = (IFerryMovable) movable;
				if (ferry.getPassengers().isEmpty() && ferry.getPosition().getOnGridDistTo(dockWater) <= FERRY_ARRIVAL_DISTANCE) {
					return ferry;
				}
			}
		}
		return null;
	}

	private int countBeachheadMovables(ShortPoint2D beachhead, EMovableType type) {
		int count = 0;
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, type)) {
			if (!parent.isReachableByLand(position) && onSameLandmass(position, beachhead)) {
				count++;
			}
		}
		return count;
	}

	private boolean onSameLandmass(ShortPoint2D a, ShortPoint2D b) {
		return landscapeGrid.isReachable(a.x, a.y, b.x, b.y, false);
	}

	/**
	 * @return the home-landmass tile nearest to the dockyard that a harbor can be constructed on and that has navigable water within dock
	 *         range, or null if none is found. Constrained to home ground so the supply line loads goods from the home economy - the
	 *         beachhead is buildable land now, but a harbor there would have nothing to ship.
	 */
	private ShortPoint2D findHomeHarborPosition(ShortPoint2D dockyardPosition) {
		AbstractConstructionMarkableMap constructionGrid = mainGrid.getConstructionMarksGrid();
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();
		ShortPoint2D best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ShortPoint2D land : parent.aiStatistics.getLandForPlayer(playerId)) {
			if (!parent.isReachableByLand(land)) {
				continue; // never place the home harbor on a beachhead
			}
			if (!constructionGrid.canConstructAt(land.x, land.y, EBuildingType.HARBOR, playerId)) {
				continue;
			}
			boolean waterInRange = jsettlers.common.map.shapes.HexGridArea.stream(land.x, land.y, 1, IDockBuilding.MAXIMUM_DOCKYARD_DISTANCE)
					.filterBounds(width, height)
					.filter((x, y) -> landscapeGrid.getLandscapeTypeAt(x, y).isWater)
					.getFirst()
					.isPresent();
			if (!waterInRange) {
				continue;
			}
			int distance = land.getOnGridDistTo(dockyardPosition);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = land;
			}
		}
		return best;
	}

	private HarborBuilding findHarbor() {
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypeForPlayer(EBuildingType.HARBOR, playerId)) {
			Building building = parent.aiStatistics.getBuildingAt(position);
			if (building instanceof HarborBuilding) {
				return (HarborBuilding) building;
			}
		}
		return null;
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

	private ShortPoint2D findDockWaterPosition(HarborBuilding harbor) {
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();
		return jsettlers.common.map.shapes.HexGridArea.stream(harbor.getPosition().x, harbor.getPosition().y, 1, IDockBuilding.MAXIMUM_DOCKYARD_DISTANCE)
				.filterBounds(width, height)
				.filter((x, y) -> landscapeGrid.getLandscapeTypeAt(x, y).isWater)
				.filter((x, y) -> harbor.canDockBePlaced(new ShortPoint2D(x, y)))
				.getFirst()
				.orElse(null);
	}

	private static List<ShortPoint2D> listOfPositions(ShortPoint2D position) {
		List<ShortPoint2D> list = new ArrayList<>(1);
		list.add(position);
		return list;
	}

	private static List<Integer> listOfIds(int id) {
		List<Integer> list = new ArrayList<>(1);
		list.add(id);
		return list;
	}
}
