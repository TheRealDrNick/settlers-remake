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
import jsettlers.common.action.SetMaterialProductionAction;
import jsettlers.common.action.SetTradingWaypointAction.EWaypointType;
import jsettlers.common.buildings.BuildingVariant;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.landscape.EResourceType;
import jsettlers.common.map.partition.IPartitionData;
import jsettlers.common.material.EMaterialType;
import jsettlers.common.material.EPriority;
import jsettlers.common.movable.EDirection;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.movable.EShipType;
import jsettlers.common.player.ECivilisation;
import jsettlers.common.position.RelativePoint;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.input.tasks.ChangeTradingRequestGuiTask;
import jsettlers.input.tasks.ConstructBuildingTask;
import jsettlers.input.tasks.ConvertGuiTask;
import jsettlers.input.tasks.EGuiAction;
import jsettlers.input.tasks.MovableGuiTask;
import jsettlers.input.tasks.OrderShipGuiTask;
import jsettlers.input.tasks.SetBuildingPriorityGuiTask;
import jsettlers.input.tasks.SetDockGuiTask;
import jsettlers.input.tasks.SetMaterialProductionGuiTask;
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
 * <li><b>Working ore mine (Phase 3)</b> - once the tower is finished and occupied (the ground is held and the tower's territory has opened up
 *     enough buildable mountain) it sinks the appropriate ore mine ({@code COALMINE}/{@code IRONMINE}/{@code GOLDMINE}) onto the beachhead
 *     deposit that motivated colonizing, then extends the same sea-trade supply line with a {@code PICK} (so a beachhead bearer becomes the
 *     {@code MINER} that mans it) and food ({@code BREAD}/{@code MEAT}, so the miner keeps working) - turning the beachhead into an actually
 *     producing outpost rather than a dead claim.</li>
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
	// the construction TOOLS among the shipped goods (HAMMER->bricklayer, BLADE->digger). Like the farmer's scythe these are made just-in-time by
	// the home toolsmith with no spare, so home production of them must be nudged (below) or the trade route has none to load and the outpost's
	// diggers/bricklayers can never be raised - construction then stalls forever even with planks/stone and carriers on site.
	private static final EMaterialType[] OUTPOST_TOOLS = { EMaterialType.HAMMER, EMaterialType.BLADE };
	// absolute count of each construction tool the home partition is told to keep producing while a beachhead outpost is being built
	private static final int TOOL_HOME_PRODUCTION = 3;
	// target amount requested per material at the harbor; topped up each heavy tick as the cargo ship consumes it
	private static final int TRADE_REQUEST_PER_MATERIAL = 8;
	// beachhead pioneers converted to bearers so the beachhead partition has local carriers to build + serve the outpost. A farm's footprint is
	// large (a big flatten + bricklay job), so keep a healthy pool of carriers that become the diggers/bricklayers.
	private static final int MIN_BEACHHEAD_BEARERS = 8;
	// only start converting pioneers into bearers once the expedition has claimed ground within this many tiles of the ore (i.e. the pioneer
	// push has reached the ore mountain foot), so the border-push is never starved before it secures the ore.
	private static final int ORE_CLAIM_RADIUS = 12;
	// enough construction goods must have arrived in the beachhead partition before we commit a tower there (so we never litter a dead site)
	private static final int MIN_DELIVERED_TO_BUILD = 1;
	// Phase 3 - ores we will sink a mine onto, in priority order, paired with the mine that extracts each. Mirrors the resources
	// ColonizationModule.neededResources() colonizes for (COAL, IRONORE, GOLDORE, plus GEMSTONE for the Egyptians). Only variants that exist
	// for the player's civilisation and are actually mines are used (see findSuppliableMinePlacement), so this stays civ-safe.
	private static final EResourceType[] MINEABLE_ORES = { EResourceType.COAL, EResourceType.IRONORE, EResourceType.GOLDORE, EResourceType.GEMSTONE };
	private static final EBuildingType[] ORE_MINES = { EBuildingType.COALMINE, EBuildingType.IRONMINE, EBuildingType.GOLDMINE, EBuildingType.GEMSMINE };
	// Phase 3 - goods added to the sea-trade supply line once a beachhead mine exists: a PICK turns a beachhead bearer into the MINER that
	// staffs it, and BREAD/MEAT are the food a miner eats to keep working (see MineBuilding.tryTakingFood + the mine XML foodOrder). Without
	// the pick the mine can never be manned; without food it stops after its ~10 free feed work packages.
	private static final EMaterialType[] MINE_TRADE_MATERIALS = { EMaterialType.PICK, EMaterialType.BREAD, EMaterialType.MEAT };
	// target amount requested per mine-supply material at the harbor; a small pick reserve plus a steady trickle of food
	private static final int MINE_TRADE_REQUEST_PER_MATERIAL = 4;
	// Economy pivot (farm outpost) - the achievable overseas producer when the reachable ore is landlocked (see the 2026-07-28 engine
	// learning): a FARM sits on the flat COASTAL beachhead (so its PLANK/STONE build goods + the farmer's SCYTHE can ship in over the same
	// sea-trade line) and is SELF-PRODUCING (the FARMER works the adjacent field, needing no ongoing shipped inputs), so it actually produces
	// CROP overseas where a mine cannot be supplied. The SCYTHE turns a beachhead bearer into the FARMER (see EMovableType.FARMER's tool).
	private static final EMaterialType FARMER_TOOL = EMaterialType.SCYTHE;
	// a small scythe reserve requested over the sea-trade line so one beachhead bearer can become the farm's FARMER (one scythe = one farmer)
	private static final int FARM_TRADE_REQUEST = 2;
	// absolute SCYTHE count the home partition is told to keep producing while a farm outpost is active, so a spare always exists to ship overseas
	private static final int FARM_SCYTHE_HOME_PRODUCTION = 2;
	// require at least this many owned, corn-plantable tiles inside a candidate farm's work area before we commit it, so we only raise a farm
	// on genuine farmland (flat grass/earth) and never strand one on a barren coast. Mirrors FarmConstructionPositionFinder's plantable score.
	private static final int MIN_FARM_PLANTABLE = 4;
	// Economy pivot (SHIP THE CROP HOME) - the beachhead farm self-produces CROP into its isolated beachhead partition where nothing consumes it,
	// so it is useless until delivered to the AI's home economy. This runs the same sea-trade line in REVERSE: a HARBOR raised on the farm's own
	// coastal territory partition (so its request stacks pull the farm's crop) whose trade route DESTINATION is a HOME coast tile. A cargo ship then
	// loads crop at the beachhead harbor and drops it in the home partition (MainGrid.dropMaterial adds the offer to the home partition's stock), so
	// the colony feeds home. The home supply harbor (driveMaterialDelivery) ships build goods the OTHER way - the two never share a harbor.
	private static final EMaterialType EXPORT_CROP = EMaterialType.CROP;
	// how much crop the beachhead export harbor keeps requested into its stacks so a cargo ship always has a load waiting; topped up each heavy tick
	// as the ship carries it away.
	private static final int EXPORT_CROP_REQUEST = 24;
	// STONE reserve the home supply harbor requests while developing a farm outpost. This island's home economy yields only a thin stone trickle
	// (home stock 0-1 all game) that its own construction consumes, so we over-request stone at HIGH priority to bank a surplus on the beachhead
	// during the early window - the tower + 6-stone farm + 4-stone export harbor together exceed a single-need trickle. The reserve funds the harbor.
	private static final int STONE_RESERVE_REQUEST = 16;
	// with two trade routes running (home->beachhead build goods, beachhead->home crop) keep up to this many cargo ships so the crop export is not
	// starved by the build-goods deliveries sharing the single ship the home supply line would otherwise order.
	private static final int MAX_CARGO_SHIPS = 2;
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
	// Per-tick snapshot of ALL owned across-water tiles (recomputed at the top of every applyHeavyRules). The AI's normal buildable-land set
	// getLandForPlayer omits big pioneer-claimed beachhead partitions: AiStatistics.isSeaReachableBeachheadPartition tests ship-reachability
	// only from the partition's lowest-x/y corner tile within a small radius, so a large coastal partition whose corner sits far inland is
	// wrongly judged unreachable and dropped. The producing FARM the beachhead needs sits exactly on such a big grassy coastal partition, so
	// this module scans the player's real owned ground itself (gated behind the dockyard, hence inert on land maps) instead of trusting that set.
	private List<ShortPoint2D> ownedForeignTiles = new ArrayList<>();

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
		// cheap pre-gate - a beachhead can only ever be reached, supplied and held via a dockyard (ColonizationModule ferries settlers from
		// one; this module ships goods and a garrison from one). Without a dockyard there is nothing this module can do, so bail out before the
		// full owned-land scan in findBeachheadLand(). This keeps the module not just behaviourally inert but *cost*-inert on the many maps
		// where the player never builds a dockyard (e.g. the land battles of the difficulty suite such as SpezialSumpf), where it would
		// otherwise scan every owned tile (tens of thousands late-game) every heavy tick only to conclude there is no beachhead.
		if (parent.aiStatistics.getTotalNumberOfBuildingTypeForPlayer(EBuildingType.DOCKYARD, playerId) < 1) {
			return;
		}
		short ferrySea = ferrySeaPartition();
		if (ferrySea == 0) {
			return; // no navigable sea from a ready dockyard - nothing can be shipped across water, so the module is inert
		}
		ownedForeignTiles = scanOwnedForeignTiles(); // snapshot the player's real owned across-water ground for this tick (see field javadoc)

		// ---- Outpost anchor: what producing outpost do we develop on the held beachhead? ------------------------------------------
		// Goods dropped by a cargo ship enter the drop tile's PartitionsGrid TERRITORY partition, and bearers distribute goods only WITHIN
		// that same territory partition - both are partition-local (walk-connectivity does NOT govern delivery). So the whole outpost -
		// delivery coast, bearers, tower, the producing building - is anchored on ONE suppliable territory partition (a PartitionsGrid id).
		// Priority mirrors the economy vision "colonize for whatever best relieves the bottleneck":
		//   1. a coast-SUPPLIABLE ore mine (an ore deposit whose claimed territory touches a cargo-ship coast) - the richest outpost, but
		//      rare because most reachable ore is landlocked (see the 2026-07-28 engine learning: a landlocked mine can never be supplied);
		//   2. else a coast-SUPPLIABLE FARM on the flat coastal beachhead - self-producing (the farmer works the adjacent field with no
		//      ongoing shipped inputs), so it is the achievable overseas economy when there is "just flat space, no suppliable ore";
		//   3. else just DEVELOP+HOLD the beachhead - the tower's territory has to open up before a farm's large footprint + plantable work
		//      area fit, so keep bearers, the supply line and the tower running until a farm (or mine) site appears.
		java.util.Set<Integer> suppliableTerritories = suppliableTerritories(ferrySea);
		Building existingMine = findBeachheadMine();
		Building existingFarm = findBeachheadFarm();
		ShortPoint2D anchor;              // owned outpost origin tile the whole supply line is anchored on
		int anchorPartition;             // TERRITORY partition that must own the outpost + a ferry-sea coast + bearers + the delivered stock
		EOutpostKind kind;               // what we are developing on the anchor's partition
		MinePlacement plannedMine = null; // non-null while a mine still has to be placed
		ShortPoint2D plannedFarm = null;  // non-null while a farm still has to be placed
		if (existingFarm != null) {
			anchor = existingFarm.getPosition();
			kind = EOutpostKind.FARM;
		} else if (existingMine != null && suppliableTerritories.contains((int) partitionsGrid.getPartitionIdAt(existingMine.getPosition().x, existingMine.getPosition().y))) {
			// an already-placed mine whose own partition owns a cargo-ship coast can actually be supplied - keep feeding it (Phase 3).
			anchor = existingMine.getPosition();
			kind = EOutpostKind.MINE;
		} else {
			MinePlacement suppliableMine = existingMine == null ? findSuppliableMinePlacement(suppliableTerritories) : null;
			if (suppliableMine != null) {
				plannedMine = suppliableMine;
				anchor = suppliableMine.position;
				kind = EOutpostKind.MINE;
			} else {
				ShortPoint2D suppliableFarm = findSuppliableFarmPlacement(suppliableTerritories);
				if (suppliableFarm != null) {
					plannedFarm = suppliableFarm;
					anchor = suppliableFarm;
					kind = EOutpostKind.FARM;
				} else {
					// no producing building fits a suppliable partition yet (a landlocked ore mine the economy may have sunk cannot be fed,
					// and no farmland-sized coastal footprint is owned yet) - just develop+hold the beachhead until one appears.
					anchor = findDevelopAnchor(ferrySea);
					if (anchor == null) {
						return; // own no beachhead ground yet
					}
					kind = EOutpostKind.HOLD;
				}
			}
		}
		anchorPartition = partitionsGrid.getPartitionIdAt(anchor.x, anchor.y);

		// "export phase" = the farm STANDS (construction finished), so the beachhead build-up is over and we pivot the sea-trade line to (a) stop
		// shipping the now-surplus planks/tools and instead carry the scarce STONE the export harbor needs, and (b) raise the export harbor + ship
		// the crop HOME. Gated on the farm being finished (not merely producing) so the harbor starts drawing stone as early as possible while home
		// still yields any - but never while the farm is under construction (a second large building would starve the farm's own stone). Until the
		// farm is finished the run is identical to the verified producing-farm baseline.
		boolean exportPhase = kind == EOutpostKind.FARM && existingFarm != null && existingFarm.isConstructionFinished();

		bootstrapBeachheadCarriers(anchor, anchorPartition, soldiersWithOrders);
		driveMaterialDelivery(anchor, anchorPartition, ferrySea, kind == EOutpostKind.FARM, exportPhase);
		buildAndOccupyTower(anchor, soldiersWithOrders);
		buildBeachheadLivinghouse(anchor, anchorPartition);
		if (kind == EOutpostKind.FARM) {
			buildAndWorkFarm(anchor, anchorPartition, plannedFarm, existingFarm);
			shipFarmCarriers(anchor, anchorPartition, existingFarm, soldiersWithOrders);
			exportFarmCrop(anchor, anchorPartition, ferrySea, existingFarm, exportPhase);
		} else if (kind == EOutpostKind.MINE) {
			buildAndWorkMine(anchor, anchorPartition, plannedMine, existingMine);
		}
	}

	// how many carriers the farm partition needs before we stop diverting pioneer waves to it (enough to build + man the farm)
	private static final int FARM_CARRIER_TARGET = MIN_BEACHHEAD_BEARERS;

	/**
	 * The farmland partition is frequently a different walk-landmass from where {@link ColonizationModule}'s pioneers come ashore (they head for
	 * the ore), so no carrier can ever walk to the farm and it stays an unbuilt, unmanned shell even though build goods + the scythe are delivered.
	 * The only way settlers cross to it is the same way they reached the beachhead at all: by ferry. This diverts the colonization pioneer ferry to
	 * a landing beside the FARM (instead of the ore) until the farm partition holds enough carriers. It re-commands the ferry every tick with
	 * {@code soldiersWithOrders == null}, overriding the ore-bound order ColonizationModule gave the same ferry earlier this tick (this module runs
	 * after it), so pioneer waves disembark on the farmland, where {@link #bootstrapBeachheadCarriers} converts them into the farm's carriers.
	 */
	private void shipFarmCarriers(ShortPoint2D farmAnchor, int farmPartition, Building existingFarm, Set<Integer> soldiersWithOrders) {
		if (existingFarm == null) {
			return; // only redirect settlers once the farm site is actually committed
		}
		int carriers = countMovablesInPartition(farmPartition, EMovableType.BEARER) + countMovablesInPartition(farmPartition, EMovableType.PIONEER);
		if (carriers >= FARM_CARRIER_TARGET) {
			return; // the farmland already has enough carriers to build + man the farm
		}
		ShortPoint2D landing = parent.aiStatistics.getSeaReachableLandingNear(playerId, farmAnchor);
		if (landing == null) {
			return; // no ship-reachable landing beside the farm - cannot ferry settlers here
		}
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, EMovableType.FERRY)) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (!(movable instanceof IFerryMovable)) {
				continue;
			}
			IFerryMovable ferry = (IFerryMovable) movable;
			if (countPioneerPassengers(ferry) == 0) {
				continue; // only redirect a ferry actually carrying our pioneers (an empty/soldier ferry is not ours to take)
			}
			if (ferry.getPosition().getOnGridDistTo(landing) <= FERRY_ARRIVAL_DISTANCE) {
				parent.taskScheduler.scheduleTask(new MovableGuiTask(EGuiAction.UNLOAD_FERRY, playerId, listOfIds(ferry.getID())));
			} else {
				parent.sendTroopsToById(listOfIds(ferry.getID()), landing, null, EMoveToType.DEFAULT); // null: override the ore-bound order
			}
		}
	}

	private int countPioneerPassengers(IFerryMovable ferry) {
		int count = 0;
		for (jsettlers.common.movable.IGraphicsMovable passenger : ferry.getPassengers()) {
			if (passenger.getMovableType() == EMovableType.PIONEER) {
				count++;
			}
		}
		return count;
	}

	/** What kind of producing outpost the beachhead supply line is currently developing (see the priority in {@link #applyHeavyRules}). */
	private enum EOutpostKind {
		MINE, FARM, HOLD
	}

	/**
	 * @return a representative buildable tile the player owns on a beachhead landmass (not reachable by land from the base), or null if the
	 *         player owns no such ground. Deterministic: {@code getLandForPlayer} iterates in a stable order and the first match is taken.
	 */
	private ShortPoint2D findBeachheadLand(ShortPoint2D orePoint) {
		ShortPoint2D best = null;
		int bestDist = Integer.MAX_VALUE;
		for (ShortPoint2D land : ownedForeignTiles) {
			if (parent.isReachableByLand(land)) {
				continue; // home landmass - not a beachhead
			}
			if (orePoint == null) {
				return land; // no ore anchor: original behaviour (first foreign owned tile)
			}
			int dist = land.getOnGridDistTo(orePoint);
			if (dist < bestDist) { // the owned beachhead tile closest to the ore we are developing toward
				bestDist = dist;
				best = land;
			}
		}
		return best;
	}

	// cap how many deposits per resource we probe for coast-suppliability (mirrors ColonizationModule), to bound the cost for large deposit lists
	private static final int MAX_SUPPLIABILITY_PROBES = 40;

	/**
	 * @return the ore deposit this colonization is developing toward. Mirrors {@link ColonizationModule#selectColonizationTarget}: prefer a
	 *         coast-SUPPLIABLE deposit (one whose ground reaches a cargo-ship coast once claimed) ranked ahead of the raw ore-value score, so this
	 *         agrees with the deposit the AI actually colonizes - which keeps the bearer-bootstrap deferral gate (ownsGroundNearOre) and the
	 *         fallback anchor pointed at the right beachhead. Falls back to the highest-value deposit if none is coast-suppliable.
	 */
	private ShortPoint2D findColonizationOrePoint() {
		DockyardBuilding dockyard = findReadyDockyard();
		ShortPoint2D dockWater = (dockyard != null && dockyard.getDock() != null) ? dockyard.getDock().getWaterPosition() : null;
		ShortPoint2D bestSuppliable = null;
		double bestSuppliableValue = 0.0;
		ShortPoint2D bestFallback = null;
		double bestFallbackValue = 0.0;
		for (EResourceType resource : colonizationOreResources()) {
			List<ShortPoint2D> reachable = dockWater != null
					? parent.aiStatistics.getSeaReachableResourceTargets(playerId, resource, dockWater)
					: parent.aiStatistics.getSeaReachableResourceTargets(playerId, resource);
			if (reachable.isEmpty()) {
				continue;
			}
			double value = dockWater != null
					? parent.aiStatistics.rateSeaReachableResourceTarget(playerId, resource, reachable, dockWater)
					: parent.aiStatistics.rateSeaReachableResourceTarget(playerId, resource, reachable);
			if (value <= 0.0) {
				continue;
			}
			ShortPoint2D repr = reachable.get(0);
			boolean reprSuppliable = false;
			if (dockWater != null) {
				int probes = 0;
				for (ShortPoint2D ore : reachable) {
					if (parent.aiStatistics.isDepositCoastSuppliable(ore, dockWater)) {
						repr = ore;
						reprSuppliable = true;
						break;
					}
					if (++probes >= MAX_SUPPLIABILITY_PROBES) {
						break;
					}
				}
			}
			if (reprSuppliable) {
				if (value > bestSuppliableValue) {
					bestSuppliableValue = value;
					bestSuppliable = repr;
				}
			} else if (value > bestFallbackValue) {
				bestFallbackValue = value;
				bestFallback = repr;
			}
		}
		return bestSuppliable != null ? bestSuppliable : bestFallback;
	}

	private List<EResourceType> colonizationOreResources() {
		List<EResourceType> resources = new ArrayList<>();
		resources.add(EResourceType.COAL);
		resources.add(EResourceType.IRONORE);
		resources.add(EResourceType.GOLDORE);
		if (parent.getPlayer().getCivilisation() == ECivilisation.EGYPTIAN) {
			resources.add(EResourceType.GEMSTONE);
		}
		return resources;
	}

	// how many landed pioneers, at most, to divert from the ore push and march into the outpost partition to become its carriers. Kept small so
	// the border/ore expedition is barely affected; only enough to staff the outpost's construction + worker.
	private static final int MAX_CARRIER_RECRUITS_PER_TICK = 3;

	/**
	 * Ensures the outpost's own TERRITORY partition has at least {@link #MIN_BEACHHEAD_BEARERS} carriers - the one prerequisite for anything to
	 * happen there, since delivery, construction and worker-creation are all partition-local and bearers/goods never cross water. Pioneers claim
	 * the big beachhead partition by walking through it but then move on to the ore, leaving it owned yet unpopulated, so two steps are needed:
	 * <ol>
	 * <li>convert idle pioneers that are standing IN the partition into bearers (they register as jobless carriers there); and</li>
	 * <li>if that is not enough, MARCH a few landed pioneers from elsewhere on the shore into the partition (a plain FORCED walk to the anchor),
	 *     so a later tick can convert them. The march passes {@code soldiersWithOrders == null} on purpose so it overrides the ore-bound order
	 *     {@link ColonizationModule} gave the same pioneers earlier this tick - this module runs after it, so our order wins and a handful of
	 *     pioneers peel off to settle the farmland while the rest keep pushing the ore.</li>
	 * </ol>
	 */
	private void bootstrapBeachheadCarriers(ShortPoint2D anchor, int outpostPartition, Set<Integer> soldiersWithOrders) {
		int existingBearers = countMovablesInPartition(outpostPartition, EMovableType.BEARER);
		int wanted = MIN_BEACHHEAD_BEARERS - existingBearers;
		if (wanted <= 0) {
			return;
		}
		List<Integer> convert = new ArrayList<>();
		List<ShortPoint2D> marchCandidates = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, EMovableType.PIONEER)) {
			if (parent.isReachableByLand(position)) {
				continue; // still on our home landmass - leave it to the home pioneer pool / Phase 1 expedition
			}
			if (partitionsGrid.getPartitionIdAt(position.x, position.y) == outpostPartition) {
				ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
				if (movable != null && convert.size() < wanted) {
					convert.add(movable.getID()); // already in the partition - convert it in place
				}
			} else {
				marchCandidates.add(position); // a landed pioneer elsewhere on the shore we could divert into the partition
			}
		}
		if (!convert.isEmpty()) {
			parent.taskScheduler.scheduleTask(new ConvertGuiTask(playerId, convert, EMovableType.BEARER));
		}
		int stillWanted = wanted - convert.size();
		// only pioneers that can actually WALK to the anchor (same walk-landmass) are worth marching; a FORCED move can't path across water.
		List<ShortPoint2D> reachable = new ArrayList<>();
		for (ShortPoint2D p : marchCandidates) {
			if (onSameLandmass(p, anchor)) {
				reachable.add(p);
			}
		}
		if (stillWanted > 0 && !reachable.isEmpty()) {
			// divert the landed pioneers nearest the anchor into the partition; they get converted once they arrive (a subsequent tick).
			reachable.sort((a, b) -> Integer.compare(a.getOnGridDistTo(anchor), b.getOnGridDistTo(anchor)));
			List<ShortPoint2D> recruits = new ArrayList<>(
					reachable.subList(0, Math.min(Math.min(stillWanted, MAX_CARRIER_RECRUITS_PER_TICK), reachable.size())));
			parent.sendTroopsTo(recruits, anchor, null, EMoveToType.FORCED); // null: override the ore-bound order so these peel off to settle
		}
	}

	private int countMovablesInPartition(int partition, EMovableType type) {
		int count = 0;
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, type)) {
			if (!parent.isReachableByLand(position) && partitionsGrid.getPartitionIdAt(position.x, position.y) == partition) {
				count++;
			}
		}
		return count;
	}

	private int partitionStock(int partition, EMaterialType material) {
		for (ShortPoint2D land : ownedForeignTiles) {
			if (!parent.isReachableByLand(land) && partitionsGrid.getPartitionIdAt(land.x, land.y) == partition) {
				IPartitionData data = partitionsGrid.getPartitionDataForManagerAt(land.x, land.y);
				return data == null ? -1 : data.getAmountOf(material);
			}
		}
		return -1;
	}

	/** @return whether the player owns a tile within {@link #ORE_CLAIM_RADIUS} of the ore, i.e. the pioneer push has reached and claimed the ore's mountain foot. */
	private boolean ownsGroundNearOre(ShortPoint2D orePoint) {
		for (ShortPoint2D land : ownedForeignTiles) {
			if (!parent.isReachableByLand(land) && land.getOnGridDistTo(orePoint) <= ORE_CLAIM_RADIUS) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Drives the sea-trade supply line that feeds the beachhead: build + dock a harbor at the home coast, order a cargo ship, point the trade
	 * route at the beachhead and keep the outpost goods requested. Each step is guarded and re-derived every tick, so partial progress is
	 * safe to repeat.
	 */
	private void driveMaterialDelivery(ShortPoint2D anchor, int minePartition, short ferrySea, boolean farmSupply, boolean exportPhase) {
		DockyardBuilding dockyard = findReadyDockyard();
		if (dockyard == null || dockyard.getDock() == null) {
			return; // cargo ships are built by the dockyard; without a working one there is no way to ship goods
		}
		// The cargo ship must sail the SAME sea partition the colonization ferry uses to reach the beachhead - otherwise it is stuck in a
		// different body of water and can never deliver to the beachhead's coast. The dockyard's own dock already sits on that sea (the ferry
		// sails from it to the beachhead), so use its sea partition as the required sea for the harbor + its dock.
		short beachheadSea = ferrySea;

		HarborBuilding harbor = findHarbor();
		if (harbor == null) {
			// no harbor yet - place one at the HOME coast (never on the beachhead, which is now also buildable land): a home-landmass tile
			// that a harbor fits on and that has water in the beachhead's sea partition within dock range, nearest to the existing dockyard.
			ShortPoint2D harborPosition = findHomeHarborPosition(dockyard.getPosition(), beachheadSea);
			if (harborPosition != null) {
				parent.taskScheduler.scheduleTask(new ConstructBuildingTask(EGuiAction.BUILD, playerId, harborPosition, EBuildingType.HARBOR));
			}
			return;
		}
		if (!harbor.isConstructionFinished()) {
			return; // still being built at home
		}
		if (harbor.getDock() == null) {
			ShortPoint2D dockWater = findDockWaterPosition(harbor, beachheadSea);
			if (dockWater != null) {
				parent.taskScheduler.scheduleTask(new SetDockGuiTask(playerId, harbor, dockWater));
			}
			return;
		}

		// Aim the trade route's destination at an owned coast tile in the MINE's own TERRITORY partition, so the cargo ship's dropped goods
		// enter that partition's stock (MainGrid.dropMaterial adds the offer to getPartitionAt(dropTile)) and that partition's own bearers can
		// carry them to the mine. Re-aim whenever the current destination is NOT already in the mine partition - comparing TERRITORY partitions
		// (getPartitionIdAt), NOT walk landmass. The old walk test saw a coast tile one territory over as "same landmass" and never corrected the
		// aim, so goods piled up in the neighbouring (tower) partition and never reached the mine - the root cause of the never-producing mine.
		// (Re-setting the DESTINATION every tick would reset the cargo ship's in-progress voyage, so we only re-aim on a real partition mismatch.)
		// Two-phase delivery target, always a VALID owned coast the cargo ship can reach (never an unowned/inland tile - aiming there
		// wastes every drop and starves the economy that develops the beachhead):
		//  Phase B (preferred): a coast in the MINE's own territory partition, so dropped goods enter the mine's stock and its bearers
		//                       carry them to the mine.
		//  Phase A (fallback):  any owned beachhead coast on the ferry sea nearest the ore. Early, before the ore pocket + its coast are
		//                       claimed, this keeps the beachhead developing (the economy builds the radius-40 towers that claim the
		//                       pocket), until a Phase-B coast exists.
		ShortPoint2D orePoint = findColonizationOrePoint();
		ShortPoint2D near = orePoint != null ? orePoint : anchor;
		ShortPoint2D mineCoast = findDeliveryCoastInPartition(minePartition, ferrySea, near);
		ShortPoint2D deliveryTarget = mineCoast != null ? mineCoast : findAnyBeachheadCoast(ferrySea, near);
		int targetPartition = deliveryTarget == null ? -1 : partitionsGrid.getPartitionIdAt(deliveryTarget.x, deliveryTarget.y);
		ShortPoint2D currentDestination = currentTradeDestination(harbor);
		int currentDestPartition = currentDestination == null ? -1 : partitionsGrid.getPartitionIdAt(currentDestination.x, currentDestination.y);
		if (deliveryTarget != null && (currentDestination == null || currentDestPartition != targetPartition)) {
			// (re-)aim only on a real territory-partition change (Phase A->B switch, or the current coast lost/re-owned) - re-setting the
			// destination every tick would reset the cargo ship's in-progress voyage.
			parent.taskScheduler.scheduleTask(new SetTradingWaypointGuiTask(EGuiAction.SET_TRADING_WAYPOINT, playerId, harbor.getPosition(),
					EWaypointType.DESTINATION, deliveryTarget));
		}
		if (farmSupply) {
			// STONE is the binding constraint of the whole colony: this island's home economy yields only a thin trickle of spare stone (home
			// stock sits at 0-1 all game) and consumes it on its own construction, so the beachhead's tower + 6-stone farm + 4-stone export
			// harbor together exceed what the trade route captures unless we compete hard for it. So throughout farm development raise the home
			// harbor to HIGH priority and request a STONE RESERVE (beyond the farm's own need) so a surplus banks on the beachhead during the
			// early window while home still yields stone - that banked reserve is what later funds the export harbor, since home yields almost no
			// stone once its own mid-game construction ramps up. Colonization-gated, so the difficulty suite is untouched.
			parent.taskScheduler.scheduleTask(new SetBuildingPriorityGuiTask(playerId, harbor.getPosition(), EPriority.HIGH));
		}
		if (exportPhase) {
			// the farm is built + producing: the beachhead is already flooded with planks + construction tools (see the 297-plank pile-up), so
			// stop shipping them and request ONLY the scarce STONE the export harbor needs - otherwise the one shared cargo ship spends every
			// voyage carrying surplus planks and never the 4 stone the harbor needs.
			for (EMaterialType surplus : new EMaterialType[] { EMaterialType.PLANK, EMaterialType.HAMMER, EMaterialType.BLADE }) {
				if (harbor.getRequestedTradingFor(surplus) > 0) {
					parent.taskScheduler.scheduleTask(new ChangeTradingRequestGuiTask(EGuiAction.CHANGE_TRADING, playerId, harbor.getPosition(),
							surplus, 0, false));
				}
			}
			if (harbor.getRequestedTradingFor(EMaterialType.STONE) < STONE_RESERVE_REQUEST) {
				parent.taskScheduler.scheduleTask(new ChangeTradingRequestGuiTask(EGuiAction.CHANGE_TRADING, playerId, harbor.getPosition(),
						EMaterialType.STONE, STONE_RESERVE_REQUEST, false));
			}
		} else {
			for (EMaterialType material : OUTPOST_TRADE_MATERIALS) {
				// while developing a farm, over-request STONE so a reserve banks for the later export harbor (see above); everything else at the
				// normal amount.
				int want = (farmSupply && material == EMaterialType.STONE) ? STONE_RESERVE_REQUEST : TRADE_REQUEST_PER_MATERIAL;
				if (harbor.getRequestedTradingFor(material) < want) {
					parent.taskScheduler.scheduleTask(new ChangeTradingRequestGuiTask(EGuiAction.CHANGE_TRADING, playerId, harbor.getPosition(),
							material, want, false));
				}
			}
		}
		// guarantee the HOME economy keeps a small spare of the construction TOOLS (HAMMER/BLADE) so the trade route always has some to ship - the
		// isolated beachhead has no toolsmith, and without a shipped hammer/blade no bricklayer/digger can be raised there and the outpost's
		// buildings never get built. Colonization-gated (needs the ready harbor+dock above), so land maps that build no harbor are unaffected.
		for (EMaterialType tool : OUTPOST_TOOLS) {
			parent.taskScheduler.scheduleTask(new SetMaterialProductionGuiTask(playerId, harbor.getPosition(), tool,
					SetMaterialProductionAction.EMaterialProductionType.SET_PRODUCTION, TOOL_HOME_PRODUCTION));
		}
		// once we are developing a FARM outpost, also ship a SCYTHE so a beachhead bearer can become the FARMER that mans it. The farm's
		// PLANK/STONE build goods + the digger/bricklayer tools already ship via OUTPOST_TRADE_MATERIALS, so the scythe is all the farm adds.
		if (farmSupply) {
			if (!exportPhase && harbor.getRequestedTradingFor(FARMER_TOOL) < FARM_TRADE_REQUEST) {
				parent.taskScheduler.scheduleTask(new ChangeTradingRequestGuiTask(EGuiAction.CHANGE_TRADING, playerId, harbor.getPosition(),
						FARMER_TOOL, FARM_TRADE_REQUEST, false));
			} else if (exportPhase && harbor.getRequestedTradingFor(FARMER_TOOL) > 0) {
				// the farm is manned; stop shipping scythes so the cargo ship is free to carry the export harbor's stone
				parent.taskScheduler.scheduleTask(new ChangeTradingRequestGuiTask(EGuiAction.CHANGE_TRADING, playerId, harbor.getPosition(),
						FARMER_TOOL, 0, false));
			}
			// the isolated beachhead has no toolsmith, so the farmer's SCYTHE must be shipped - but the HOME economy makes tools just-in-time and
			// rarely keeps a spare, so the trade route has none to load. Nudge the home partition to hold a small absolute SCYTHE production so a
			// couple always exist to ship. Colonization-gated, so this never touches the difficulty maps (they build no dockyard/harbor).
			parent.taskScheduler.scheduleTask(new SetMaterialProductionGuiTask(playerId, harbor.getPosition(), FARMER_TOOL,
					SetMaterialProductionAction.EMaterialProductionType.SET_PRODUCTION, FARM_SCYTHE_HOME_PRODUCTION));
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

		boolean goods = hasDeliveredConstructionGoods(beachhead);
		ShortPoint2D towerPosition = goods ? findBeachheadBuildPosition(beachhead, -1, EBuildingType.TOWER) : null;
		if (!goods) {
			return; // wait until the sea-trade supply line has actually landed building material on the beachhead
		}
		if (towerPosition != null) {
			parent.taskScheduler.scheduleTask(new ConstructBuildingTask(EGuiAction.BUILD, playerId, towerPosition, EBuildingType.TOWER));
		}
	}

	/** @return whether the player has any finished, occupied military building on a foreign (across-water) landmass, i.e. the beachhead is held. */
	private boolean hasOccupiedForeignMilitaryBuilding() {
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, playerId)) {
			if (parent.isReachableByLand(position)) {
				continue; // home landmass
			}
			Building building = parent.aiStatistics.getBuildingAt(position);
			if (building != null && building.isConstructionFinished() && building.isOccupied()) {
				return true;
			}
		}
		return false;
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

	/**
	 * @return a buildable beachhead tile of {@code type} nearest {@code beachhead} on its landmass, or null if none is currently constructable.
	 *         When {@code partition >= 0} only tiles in that TERRITORY partition qualify (so e.g. a living house's spawned bearers land in the
	 *         outpost's own suppliable partition); pass {@code -1} for no partition filter (the tower, which just needs to hold the landmass).
	 */
	private ShortPoint2D findBeachheadBuildPosition(ShortPoint2D beachhead, int partition, EBuildingType type) {
		AbstractConstructionMarkableMap constructionGrid = mainGrid.getConstructionMarksGrid();
		ShortPoint2D best = null;
		int bestDist = Integer.MAX_VALUE;
		for (ShortPoint2D land : ownedForeignTiles) {
			if (parent.isReachableByLand(land) || !onSameLandmass(land, beachhead)) {
				continue; // only beachhead ground on the landmass we are developing
			}
			if (partition >= 0 && partitionsGrid.getPartitionIdAt(land.x, land.y) != partition) {
				continue; // must sit in the requested territory partition (so spawned bearers land where they are needed)
			}
			if (!constructionGrid.canConstructAt(land.x, land.y, type, playerId)) {
				continue;
			}
			// prefer the buildable tile nearest the beachhead anchor (the owned tile nearest the ore), so the tower's territory reaches the ore
			int dist = land.getOnGridDistTo(beachhead);
			if (dist < bestDist) {
				bestDist = dist;
				best = land;
			}
		}
		return best;
	}

	/**
	 * Grows the beachhead's labor pool so the colony can build up beyond a bare farm. Raises a SMALL_LIVINGHOUSE on the outpost's own territory
	 * partition: it is a {@code SpawnBuilding} whose bearer-type worker spawns a jobless {@code BEARER} into that partition every ~2s (self-occupied,
	 * self-funding its beds), which is the only local growth source since bearers never cross water. More local bearers = the colony can staff more
	 * building + worker jobs (each digger/bricklayer/farmer is a jobless bearer + a shipped tool). Gated on the beachhead tower being FINISHED (defend
	 * first, and don't make the house compete with the tower for the first diggers) and on construction goods having arrived. Colonization only ever
	 * needs one, so an existing foreign living house short-circuits. The 2 PLANK + 3 STONE it costs already ship over the existing supply line.
	 */
	private void buildBeachheadLivinghouse(ShortPoint2D anchor, int anchorPartition) {
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypeForPlayer(EBuildingType.SMALL_LIVINGHOUSE, playerId)) {
			if (onSameLandmass(position, anchor)) {
				return; // already have one on the beachhead landmass
			}
		}
		Building tower = findBeachheadMilitaryBuilding(anchor);
		if (tower == null || !tower.isConstructionFinished()) {
			return; // hold the beachhead first; don't contend with the tower for the first diggers
		}
		if (!hasDeliveredConstructionGoods(anchor)) {
			return; // wait until the supply line has landed build material on the beachhead
		}
		ShortPoint2D position = findBeachheadBuildPosition(anchor, anchorPartition, EBuildingType.SMALL_LIVINGHOUSE);
		if (position != null) {
			parent.taskScheduler.scheduleTask(new ConstructBuildingTask(EGuiAction.BUILD, playerId, position, EBuildingType.SMALL_LIVINGHOUSE));
		}
	}

	/**
	 * Phase 3 - once the beachhead is actually held (a finished, occupied tower), sink an ore mine onto the beachhead deposit that motivated
	 * colonizing and get it working. Building material for the mine already arrives over the Phase 2 supply line (PLANK/STONE); this method
	 * places the mine and then extends the same supply line with the two extra goods a working mine needs on the isolated beachhead: a
	 * {@code PICK} (so a beachhead bearer becomes the {@code MINER} that mans it) and food ({@code BREAD}/{@code MEAT}, so the miner keeps
	 * working past the mine's ~10 free feed work packages). Gated on the occupied tower so we only invest once the ground is defended and the
	 * tower's territory has opened up enough buildable mountain to fit a mine.
	 */
	private void buildAndWorkMine(ShortPoint2D minePosition, int minePartition, MinePlacement plannedMine, Building existingMine) {
		// The beachhead can span several land partitions (mountain ridges / inlets split the island), so the occupied tower that holds the
		// original coastal landing is often NOT on the same land partition as the ore. Gate the mine on the colonization being defended *somewhere*
		// on the foreign shore (any occupied foreign military building) rather than on a tower on the ore's exact partition.
		if (!hasOccupiedForeignMilitaryBuilding()) {
			return; // hold the beachhead first (Phase 2); an undefended beachhead is no place to sink a mine
		}
		if (existingMine == null) {
			if (plannedMine == null) {
				return; // no suppliable mineable ore deposit found yet - keep developing/pushing the beachhead
			}
			parent.taskScheduler.scheduleTask(new ConstructBuildingTask(EGuiAction.BUILD, playerId, plannedMine.position, plannedMine.mine));
			return; // nothing to supply until the mine site exists
		}

		// the mine is placed/standing: keep the pick + food requested so the mine's OWN partition can staff and feed it over the sea trade route
		HarborBuilding harbor = findHarbor();
		if (harbor == null || !harbor.isConstructionFinished() || harbor.getDock() == null) {
			return;
		}
		for (EMaterialType material : MINE_TRADE_MATERIALS) {
			if (harbor.getRequestedTradingFor(material) < MINE_TRADE_REQUEST_PER_MATERIAL) {
				parent.taskScheduler.scheduleTask(new ChangeTradingRequestGuiTask(EGuiAction.CHANGE_TRADING, playerId, harbor.getPosition(),
						material, MINE_TRADE_REQUEST_PER_MATERIAL, false));
			}
		}
	}

	/**
	 * @return the player's ore mine on a foreign (across-water) landmass (finished or under construction), or null if none exists yet. Matches any
	 *         foreign mine rather than restricting to the anchor's walk region: a mine placed at a mountain foot can straddle walk partitions (its
	 *         origin tile and its door tile in different blocked partitions), so a walk-region match against a single anchor could miss it and lead
	 *         to placing a duplicate. Since colonization only ever founds one outpost mine, "any foreign mine" is the right existence check.
	 */
	private Building findBeachheadMine() {
		for (EBuildingType mineType : ORE_MINES) {
			for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypeForPlayer(mineType, playerId)) {
				if (!parent.isReachableByLand(position)) {
					return parent.aiStatistics.getBuildingAt(position);
				}
			}
		}
		return null;
	}

	/** @return the sea blocked-partition the colonization ferry sails (the ready dockyard's dock water), or 0 (BLOCKED, no real sea) if none. */
	private short ferrySeaPartition() {
		DockyardBuilding dockyard = findReadyDockyard();
		if (dockyard == null || dockyard.getDock() == null) {
			return 0;
		}
		ShortPoint2D water = dockyard.getDock().getWaterPosition();
		return landscapeGrid.getBlockedPartitionAt(water.x, water.y);
	}

	/**
	 * Recommendation #1/#2 made concrete: find the best ore deposit that can actually be MINED AND SUPPLIED. A mine can only be built and worked
	 * if construction goods (PLANK/STONE), a PICK and food can reach it - and goods cross water only by sea-trade dropped on a coast, then bearers
	 * distribute them within one walkable owned region. So a mineable-ore tile is "suppliable" iff its own walkable owned region (blocked land
	 * partition) also owns a coast tile bordering the ferry's sea (which the cargo ship can reach). This resolves the blocked-vs-territory
	 * partition mismatch: we anchor on the mine's walk region, which for a mine built at the mountain foot is the coastal region, not the
	 * landlocked mountain interior.
	 *
	 * @return the best-scoring suppliable mine placement (most ore under the mine), or null if no reachable deposit is currently suppliable.
	 */
	private MinePlacement findSuppliableMinePlacement(java.util.Set<Integer> suppliableTerritories) {
		AbstractConstructionMarkableMap constructionGrid = mainGrid.getConstructionMarksGrid();
		ECivilisation civilisation = parent.getPlayer().getCivilisation();
		// 2. best mineable-ore tile that sits in a suppliable territory partition. We test the mine ORIGIN tile's partition (owned buildable
		// ground), which is where the construction stacks live and where the delivered goods are consumed.
		MinePlacement best = null;
		int bestAmount = 0;
		for (int i = 0; i < MINEABLE_ORES.length; i++) {
			BuildingVariant variant = ORE_MINES[i].getVariant(civilisation);
			if (variant == null || !variant.isMine()) {
				continue; // this civilisation has no such mine
			}
			for (ShortPoint2D land : ownedForeignTiles) {
				if (parent.isReachableByLand(land)) {
					continue;
				}
				if (!suppliableTerritories.contains((int) partitionsGrid.getPartitionIdAt(land.x, land.y))) {
					continue; // the mine's own territory partition owns no cargo-ship-reachable coast - it could never be supplied
				}
				if (!constructionGrid.canConstructAt(land.x, land.y, variant.getType(), playerId)) {
					continue;
				}
				int amount = oreUnderMine(variant, land, MINEABLE_ORES[i]);
				if (amount > bestAmount) {
					bestAmount = amount;
					ShortPoint2D door = variant.getDoorTile().calculatePoint(land);
					best = new MinePlacement(variant.getType(), land, mainGrid.isInBounds(door.x, door.y) ? door : land);
				}
			}
		}
		return best;
	}

	/**
	 * @return every across-water tile the player owns this tick (a full-grid scan; only owned tiles pay the land-reachability test). Used in place
	 *         of {@code getLandForPlayer} for the beachhead because that set drops large pioneer-claimed coastal partitions (see the
	 *         {@link #ownedForeignTiles} field javadoc). Cheap enough to run every heavy tick because the whole module is gated behind a dockyard,
	 *         so on land maps (the difficulty suite) it is never reached.
	 */
	private List<ShortPoint2D> scanOwnedForeignTiles() {
		List<ShortPoint2D> tiles = new ArrayList<>();
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();
		for (short x = 0; x < width; x++) {
			for (short y = 0; y < height; y++) {
				if (partitionsGrid.getPlayerIdAt(x, y) != playerId) {
					continue; // not owned by us
				}
				ShortPoint2D tile = new ShortPoint2D(x, y);
				if (parent.isReachableByLand(tile)) {
					continue; // home landmass - not a beachhead
				}
				tiles.add(tile);
			}
		}
		return tiles;
	}

	/**
	 * @return the set of foreign (across-water) TERRITORY partitions (PartitionsGrid ids) the player owns that ALSO own a coast bordering the
	 *         ferry's sea. Goods dropped by a cargo ship enter the drop tile's territory partition and bearers distribute only within that same
	 *         partition, so an outpost (mine or farm) is suppliable iff its OWN territory partition is in this set. Computed once per heavy tick
	 *         and shared by the mine + farm placement so they agree on which partitions can actually be fed.
	 */
	private java.util.Set<Integer> suppliableTerritories(short ferrySea) {
		java.util.Set<Integer> territories = new java.util.HashSet<>();
		for (ShortPoint2D land : ownedForeignTiles) {
			if (parent.isReachableByLand(land)) {
				continue; // home landmass
			}
			int territory = partitionsGrid.getPartitionIdAt(land.x, land.y);
			if (territories.contains(territory)) {
				continue;
			}
			if (bordersSea(land, ferrySea)) {
				territories.add(territory);
			}
		}
		return territories;
	}

	/**
	 * @return the player's FARM on a foreign (across-water) landmass (finished or under construction), or null if none exists yet. Colonization
	 *         only ever raises one beachhead farm, so "any foreign farm" is the right existence check (mirrors {@link #findBeachheadMine}).
	 */
	private Building findBeachheadFarm() {
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypeForPlayer(EBuildingType.FARM, playerId)) {
			if (!parent.isReachableByLand(position)) {
				return parent.aiStatistics.getBuildingAt(position);
			}
		}
		return null;
	}

	/**
	 * Economy pivot: find where to raise a self-producing FARM on the flat coastal beachhead. A farm is buildable + suppliable + worth raising iff
	 * its origin sits in a suppliable territory partition (its build goods + the farmer's scythe can ship in), the whole farm footprint is
	 * constructable there, and its work area covers enough owned, corn-plantable ground (flat grass/earth) to actually grow crop. Mirrors
	 * {@link jsettlers.ai.construction.FarmConstructionPositionFinder}'s plantable score; picks the position with the most plantable work area.
	 *
	 * @return the best farm origin tile, or null if no suppliable coastal farmland footprint is owned yet.
	 */
	private ShortPoint2D findSuppliableFarmPlacement(java.util.Set<Integer> suppliableTerritories) {
		if (suppliableTerritories.isEmpty()) {
			return null;
		}
		BuildingVariant farm = EBuildingType.FARM.getVariant(parent.getPlayer().getCivilisation());
		if (farm == null) {
			return null; // civilisation without a farm (should not happen - all four have one)
		}
		AbstractConstructionMarkableMap constructionGrid = mainGrid.getConstructionMarksGrid();
		RelativePoint[] workArea = farmWorkArea(farm);
		ShortPoint2D best = null;
		int bestScore = MIN_FARM_PLANTABLE - 1; // only accept a footprint with at least MIN_FARM_PLANTABLE plantable work-area tiles
		for (ShortPoint2D land : ownedForeignTiles) {
			if (parent.isReachableByLand(land)) {
				continue; // home landmass
			}
			if (!suppliableTerritories.contains((int) partitionsGrid.getPartitionIdAt(land.x, land.y))) {
				continue; // the farm's own territory partition owns no cargo-ship coast - it could never be supplied
			}
			if (!isFarmGround(landscapeGrid.getLandscapeTypeAt(land.x, land.y))) {
				continue; // cheap pre-filter: a farm origin must sit on flat grass/earth. Skips the ~90-tile canConstructAt footprint check
			}         // on the many mountain/sand/rock tiles of a big beachhead partition, which keeps this per-tick scan affordable.
			if (!constructionGrid.canConstructAt(land.x, land.y, EBuildingType.FARM, playerId)) {
				continue;
			}
			int score = plantableOwnedInWorkArea(land, workArea);
			if (score > bestScore) {
				bestScore = score;
				best = land;
			}
		}
		return best;
	}

	/** @return whether a farm can stand on this ground (flat grass/earth/flattened - the farm.xml {@code <ground>} types). */
	private static boolean isFarmGround(jsettlers.common.landscape.ELandscapeType type) {
		return type == jsettlers.common.landscape.ELandscapeType.GRASS
				|| type == jsettlers.common.landscape.ELandscapeType.EARTH
				|| type == jsettlers.common.landscape.ELandscapeType.FLATTENED;
	}

	/** @return the relative work-area points of a farm (its work centre expanded by the work radius), mirroring PlantingBuildingConstructionPositionFinder. */
	private RelativePoint[] farmWorkArea(BuildingVariant farm) {
		List<RelativePoint> points = new ArrayList<>();
		RelativePoint center = farm.getDefaultWorkcenter();
		short workRadius = farm.getWorkRadius();
		for (short x = (short) -workRadius; x < workRadius; x++) {
			for (short y = (short) -workRadius; y < workRadius; y++) {
				if (Math.sqrt(x * x + y * y) <= workRadius) {
					points.add(new RelativePoint(center.getDx() + x, center.getDy() + y));
				}
			}
		}
		return points.toArray(new RelativePoint[0]);
	}

	/** @return how many tiles of a farm placed at {@code origin} that the player owns are corn-plantable (flat grass/earth) - the farm's yield potential. */
	private int plantableOwnedInWorkArea(ShortPoint2D origin, RelativePoint[] workArea) {
		int score = 0;
		for (RelativePoint relative : workArea) {
			ShortPoint2D point = relative.calculatePoint(origin);
			if (mainGrid.isInBounds(point.x, point.y)
					&& partitionsGrid.getPlayerIdAt(point.x, point.y) == playerId
					&& mainGrid.isCornPlantable(point)) {
				score++;
			}
		}
		return score;
	}

	/**
	 * Economy pivot: once the beachhead is held (a finished, occupied tower), raise a FARM on the suppliable coastal farmland and let a beachhead
	 * bearer become its FARMER. The farm's PLANK/STONE build goods, the digger/bricklayer construction tools and the farmer's SCYTHE all ship over
	 * the Phase 2 supply line (see {@link #driveMaterialDelivery}); once built and manned the farmer works the adjacent field on its own, so the
	 * farm is self-producing and accumulates CROP in the (isolated) beachhead partition - the achievable overseas producer.
	 */
	private void buildAndWorkFarm(ShortPoint2D farmPosition, int farmPartition, ShortPoint2D plannedFarm, Building existingFarm) {
		if (!hasOccupiedForeignMilitaryBuilding()) {
			return; // hold the beachhead first (Phase 2); an undefended beachhead is no place to raise a farm
		}
		if (existingFarm == null) {
			if (plannedFarm == null) {
				return; // no suppliable farmland footprint owned yet - keep developing/holding the beachhead
			}
			parent.taskScheduler.scheduleTask(new ConstructBuildingTask(EGuiAction.BUILD, playerId, plannedFarm, EBuildingType.FARM));
			return; // nothing more to do until the farm site exists
		}
		// the farm is placed/standing: the SCYTHE that turns a beachhead bearer into its FARMER is kept requested by driveMaterialDelivery, and
		// the farmer then works the adjacent field with no further shipped inputs, so a built + manned farm needs nothing else from us.
	}

	/**
	 * Economy pivot - ship the beachhead farm's CROP HOME so the colony actually feeds the AI's home economy. The farm self-produces crop into the
	 * isolated beachhead partition where nothing consumes it; this runs the same sea-trade line in REVERSE: it raises a HARBOR on the farm's own
	 * coastal territory partition (so the harbor's request stacks pull the farm's crop), docks it on the ferry sea, aims its trade route at a HOME
	 * coast tile and requests CROP as an export. A cargo ship then loads crop at the beachhead harbor and drops it in the home partition, so home
	 * crop rises directly from the colony. Each step is guarded and re-derived every tick, so partial progress is safe to repeat.
	 */
	private void exportFarmCrop(ShortPoint2D farmAnchor, int farmPartition, short ferrySea, Building existingFarm, boolean exportPhase) {
		if (!exportPhase) {
			// do NOT raise the export harbor until the farm is FINISHED: while the farm is still under construction a second large building would
			// contend for the beachhead's scarce stone + diggers/bricklayers and neither would finish. Once the farm stands, its crop will flow to
			// this harbor's request stacks as it is produced. Until the farm is finished the run is identical to the verified producing-farm baseline.
			return;
		}
		DockyardBuilding dockyard = findReadyDockyard();
		if (dockyard == null || dockyard.getDock() == null) {
			return; // cargo ships (and a second one for the export) are built by the dockyard
		}
		HarborBuilding exportHarbor = findBeachheadHarbor();
		if (exportHarbor == null) {
			// no beachhead harbor yet - place one on a buildable coastal tile in the FARM's own territory partition (so its stacks can pull the
			// farm's crop), built from the planks/stone the home supply line already lands on the beachhead.
			ShortPoint2D harborPosition = findBeachheadHarborPosition(farmPartition, ferrySea, farmAnchor);
			if (harborPosition != null) {
				parent.taskScheduler.scheduleTask(new ConstructBuildingTask(EGuiAction.BUILD, playerId, harborPosition, EBuildingType.HARBOR));
			}
			return;
		}
		if (!exportHarbor.isConstructionFinished()) {
			return; // still being built on the beachhead
		}
		if (exportHarbor.getDock() == null) {
			ShortPoint2D dockWater = findDockWaterPosition(exportHarbor, ferrySea);
			if (dockWater != null) {
				parent.taskScheduler.scheduleTask(new SetDockGuiTask(playerId, exportHarbor, dockWater));
			}
			return;
		}
		// aim the export route's destination at an owned HOME coast tile on the ferry sea so the cargo ship drops the crop into the home partition
		// (MainGrid.dropMaterial adds it to that tile's partition stock). Set it ONCE and never re-aim: home does not move, and re-setting the
		// destination clears the waypoint list and would reset a crop voyage already in progress.
		ShortPoint2D currentDestination = currentTradeDestination(exportHarbor);
		ShortPoint2D homeCoast = currentDestination == null ? findHomeCoast(ferrySea, dockyard.getPosition()) : currentDestination;
		if (currentDestination == null && homeCoast != null) {
			parent.taskScheduler.scheduleTask(new SetTradingWaypointGuiTask(EGuiAction.SET_TRADING_WAYPOINT, playerId, exportHarbor.getPosition(),
					EWaypointType.DESTINATION, homeCoast));
		}
		if (exportHarbor.getRequestedTradingFor(EXPORT_CROP) < EXPORT_CROP_REQUEST) {
			parent.taskScheduler.scheduleTask(new ChangeTradingRequestGuiTask(EGuiAction.CHANGE_TRADING, playerId, exportHarbor.getPosition(),
					EXPORT_CROP, EXPORT_CROP_REQUEST, false));
		}
		// order a second cargo ship so the crop export is not starved by the home supply line's build-goods deliveries sharing the one ship.
		if (parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, EMovableType.CARGO_SHIP).size() < MAX_CARGO_SHIPS) {
			parent.taskScheduler.scheduleTask(new OrderShipGuiTask(playerId, dockyard, EShipType.CARGO_SHIP));
		}
	}

	/** @return the player's beachhead EXPORT harbor (a harbor on a foreign / across-water landmass), or null if none is built yet. Colonization
	 *          only ever raises one, so "any foreign harbor" is the right existence check (mirrors {@link #findBeachheadFarm}). */
	private HarborBuilding findBeachheadHarbor() {
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypeForPlayer(EBuildingType.HARBOR, playerId)) {
			if (parent.isReachableByLand(position)) {
				continue; // the home supply harbor
			}
			Building building = parent.aiStatistics.getBuildingAt(position);
			if (building instanceof HarborBuilding) {
				return (HarborBuilding) building;
			}
		}
		return null;
	}

	/**
	 * @return a buildable HARBOR position in the FARM's own territory partition that has navigable ferry-sea water within dock range (so the export
	 *         cargo ship can sail from it), nearest to the farm; or null if none is owned yet. The harbor must sit in the farm's partition so its
	 *         request stacks pull the farm's crop, which enters that partition's stock.
	 */
	private ShortPoint2D findBeachheadHarborPosition(int farmPartition, short ferrySea, ShortPoint2D farmAnchor) {
		AbstractConstructionMarkableMap constructionGrid = mainGrid.getConstructionMarksGrid();
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();
		ShortPoint2D best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ShortPoint2D land : ownedForeignTiles) {
			if (parent.isReachableByLand(land)) {
				continue; // must be on the beachhead
			}
			if (partitionsGrid.getPartitionIdAt(land.x, land.y) != farmPartition) {
				continue; // must be in the farm's own territory partition so the harbor's stacks pull the farm's crop
			}
			if (!constructionGrid.canConstructAt(land.x, land.y, EBuildingType.HARBOR, playerId)) {
				continue;
			}
			boolean waterInRange = jsettlers.common.map.shapes.HexGridArea.stream(land.x, land.y, 1, IDockBuilding.MAXIMUM_DOCKYARD_DISTANCE)
					.filterBounds(width, height)
					.filter((x, y) -> landscapeGrid.getLandscapeTypeAt(x, y).isWater)
					.filter((x, y) -> landscapeGrid.getBlockedPartitionAt(x, y) == ferrySea)
					.getFirst()
					.isPresent();
			if (!waterInRange) {
				continue;
			}
			int distance = land.getOnGridDistTo(farmAnchor);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = land;
			}
		}
		return best;
	}

	/**
	 * @return an owned HOME-landmass coast tile bordering the ferry sea (so a cargo ship can dock beside it and drop crop into the home partition),
	 *         nearest to {@code near}; or null if the home coast owns no ferry-sea tile. This is the export route's destination.
	 */
	private ShortPoint2D findHomeCoast(short ferrySea, ShortPoint2D near) {
		ShortPoint2D best = null;
		int bestDist = Integer.MAX_VALUE;
		for (ShortPoint2D land : parent.aiStatistics.getLandForPlayer(playerId)) {
			if (!parent.isReachableByLand(land)) {
				continue; // must be home ground so the drop enters the home partition
			}
			if (!bordersSea(land, ferrySea)) {
				continue; // and border water the cargo ship can actually reach
			}
			int dist = land.getOnGridDistTo(near);
			if (dist < bestDist) {
				bestDist = dist;
				best = land;
			}
		}
		return best;
	}

	/**
	 * @return a suppliable, owned coastal beachhead tile to anchor delivery/bearers/tower on while no producing building fits yet: an owned
	 *         foreign coast tile on the ferry sea nearest the ore (so its partition can be fed), or - failing that - the owned foreign tile
	 *         nearest the ore. Null if the player owns no foreign ground at all.
	 */
	private ShortPoint2D findDevelopAnchor(short ferrySea) {
		ShortPoint2D orePoint = findColonizationOrePoint();
		ShortPoint2D anyOwned = findBeachheadLand(orePoint);
		if (anyOwned == null) {
			return null; // own no foreign ground yet
		}
		ShortPoint2D near = orePoint != null ? orePoint : anyOwned;
		ShortPoint2D coast = findAnyBeachheadCoast(ferrySea, near);
		return coast != null ? coast : anyOwned;
	}

	/** @return whether {@code land} has a neighbouring water tile in sea blocked-partition {@code sea} (i.e. a cargo ship on that sea can dock beside it). */
	private boolean bordersSea(ShortPoint2D land, short sea) {
		for (EDirection dir : EDirection.VALUES) {
			int wx = dir.gridDeltaX + land.x;
			int wy = dir.gridDeltaY + land.y;
			if (mainGrid.isInBounds(wx, wy) && landscapeGrid.getLandscapeTypeAt(wx, wy).isWater
					&& landscapeGrid.getBlockedPartitionAt(wx, wy) == sea) {
				return true;
			}
		}
		return false;
	}

	/** @return the total amount of {@code ore} on the tiles a mine of {@code variant} placed at {@code position} would occupy. */
	private int oreUnderMine(BuildingVariant variant, ShortPoint2D position, EResourceType ore) {
		int amount = 0;
		for (RelativePoint relative : variant.getBlockedTiles()) {
			int x = position.x + relative.getDx();
			int y = position.y + relative.getDy();
			if (landscapeGrid.getResourceTypeAt(x, y) == ore) {
				amount += landscapeGrid.getResourceAmountAt(x, y);
			}
		}
		return amount;
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

	private boolean onSameLandmass(ShortPoint2D a, ShortPoint2D b) {
		return landscapeGrid.isReachable(a.x, a.y, b.x, b.y, false);
	}

	/**
	 * @return the home-landmass tile nearest to the dockyard that a harbor can be constructed on and that has navigable water within dock
	 *         range, or null if none is found. Constrained to home ground so the supply line loads goods from the home economy - the
	 *         beachhead is buildable land now, but a harbor there would have nothing to ship.
	 */
	private ShortPoint2D findHomeHarborPosition(ShortPoint2D dockyardPosition, short beachheadSea) {
		AbstractConstructionMarkableMap constructionGrid = mainGrid.getConstructionMarksGrid();
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();
		ShortPoint2D best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ShortPoint2D land : parent.aiStatistics.getLandForPlayer(playerId)) { // HOME tiles - the harbor loads goods from the home economy
			if (!parent.isReachableByLand(land)) {
				continue; // never place the home harbor on a beachhead
			}
			if (!constructionGrid.canConstructAt(land.x, land.y, EBuildingType.HARBOR, playerId)) {
				continue;
			}
			// require water within dock range that is on the BEACHHEAD's sea partition, so the harbor's cargo ship sails the same body of water
			// the ferry uses and can actually reach the beachhead coast (not a landlocked bay on a different sea).
			boolean waterInRange = jsettlers.common.map.shapes.HexGridArea.stream(land.x, land.y, 1, IDockBuilding.MAXIMUM_DOCKYARD_DISTANCE)
					.filterBounds(width, height)
					.filter((x, y) -> landscapeGrid.getLandscapeTypeAt(x, y).isWater)
					.filter((x, y) -> landscapeGrid.getBlockedPartitionAt(x, y) == beachheadSea)
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

	/**
	 * @return a land tile the player owns on the SAME land partition as {@code beachhead} (the mine/ore anchor) that borders water the cargo ship
	 *         can actually reach (same sea partition as the harbor's dock), nearest to the ore - i.e. the coast tile a cargo ship should sail to so
	 *         its dropped goods land in the mine's partition. Null if the ore's partition has no such cargo-ship-reachable coast.
	 */
	private ShortPoint2D findDeliveryCoastInPartition(int minePartition, short ferrySea, ShortPoint2D near) {
		ShortPoint2D best = null;
		int bestDist = Integer.MAX_VALUE;
		for (ShortPoint2D land : ownedForeignTiles) {
			if (parent.isReachableByLand(land)) {
				continue; // home landmass
			}
			if (partitionsGrid.getPartitionIdAt(land.x, land.y) != minePartition) {
				continue; // must be in the mine's OWN territory partition so dropped goods enter the mine's stock
			}
			if (!bordersSea(land, ferrySea)) {
				continue; // and border water the cargo ship can actually reach
			}
			int dist = land.getOnGridDistTo(near);
			if (dist < bestDist) {
				bestDist = dist;
				best = land;
			}
		}
		return best;
	}

	/**
	 * @return any owned beachhead tile (across water) bordering the ferry sea, nearest to {@code near}, regardless of territory partition.
	 *         Used as the delivery target while the mine's own partition does not yet own a cargo-ship coast: it keeps goods flowing to a
	 *         real, owned coast so the economy can keep developing the beachhead (build the radius-40 towers that claim the ore pocket),
	 *         instead of stranding the trade route on an unowned/inland tile.
	 */
	private ShortPoint2D findAnyBeachheadCoast(short ferrySea, ShortPoint2D near) {
		ShortPoint2D best = null;
		int bestDist = Integer.MAX_VALUE;
		for (ShortPoint2D land : ownedForeignTiles) {
			if (parent.isReachableByLand(land)) {
				continue; // home landmass
			}
			if (!bordersSea(land, ferrySea)) {
				continue;
			}
			int dist = land.getOnGridDistTo(near);
			if (dist < bestDist) {
				bestDist = dist;
				best = land;
			}
		}
		return best;
	}

	/** @return the harbor's current trade DESTINATION (the last waypoint), or null if none is set yet. */
	private ShortPoint2D currentTradeDestination(HarborBuilding harbor) {
		ShortPoint2D destination = null;
		for (java.util.Iterator<ShortPoint2D> it = harbor.getWaypointsIterator(); it.hasNext();) {
			destination = it.next();
		}
		return destination;
	}

	/** @return the HOME-coast supply harbor (the one that ships build goods TO the beachhead), or null if none is built yet. Restricted to a home
	 *          landmass tile so it never returns the beachhead EXPORT harbor {@link #exportFarmCrop} raises (which ships crop the other way). */
	private HarborBuilding findHarbor() {
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypeForPlayer(EBuildingType.HARBOR, playerId)) {
			if (!parent.isReachableByLand(position)) {
				continue; // a foreign harbor is the beachhead export harbor, not the home supply harbor
			}
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

	private ShortPoint2D findDockWaterPosition(HarborBuilding harbor, short beachheadSea) {
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();
		return jsettlers.common.map.shapes.HexGridArea.stream(harbor.getPosition().x, harbor.getPosition().y, 1, IDockBuilding.MAXIMUM_DOCKYARD_DISTANCE)
				.filterBounds(width, height)
				.filter((x, y) -> landscapeGrid.getLandscapeTypeAt(x, y).isWater)
				.filter((x, y) -> landscapeGrid.getBlockedPartitionAt(x, y) == beachheadSea) // dock on the beachhead's sea so the cargo ship can reach it
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

	/**
	 * A chosen beachhead mine: the mine building type, the buildable position it sits on, and the supply anchor - the mine's DOOR tile, i.e. the
	 * walkable tile where bearers deliver goods. The whole beachhead development (delivery coast, tower, bearers) is anchored on this door tile's
	 * walk region, because a mine built at a mountain foot can have its origin tile on a landlocked mountainborder partition while its door sits on
	 * coast-connected walkable ground; supply reaches the mine only through the door's walk region.
	 */
	private static final class MinePlacement {
		final EBuildingType mine;
		final ShortPoint2D position;
		final ShortPoint2D anchor;

		MinePlacement(EBuildingType mine, ShortPoint2D position, ShortPoint2D anchor) {
			this.mine = mine;
			this.position = position;
			this.anchor = anchor;
		}
	}
}
