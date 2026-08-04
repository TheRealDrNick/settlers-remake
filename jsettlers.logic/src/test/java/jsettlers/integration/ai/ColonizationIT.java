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
package jsettlers.integration.ai;

import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.concurrent.Executors;

import org.junit.Assert;
import org.junit.Test;

import jsettlers.ai.highlevel.AiStatistics;
import jsettlers.common.CommonConstants;
import jsettlers.common.ai.EPlayerType;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.landscape.EResourceType;
import jsettlers.common.landscape.ELandscapeType;
import jsettlers.algorithms.construction.AbstractConstructionMarkableMap;
import jsettlers.logic.map.grid.landscape.LandscapeGrid;
import jsettlers.common.material.EMaterialType;
import jsettlers.common.menu.IStartedGame;
import jsettlers.common.player.ECivilisation;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.constants.Constants;
import jsettlers.logic.constants.MatchConstants;
import jsettlers.logic.map.grid.MainGrid;
import jsettlers.logic.map.grid.partition.PartitionsGrid;
import jsettlers.logic.map.loading.MapLoadException;
import jsettlers.logic.map.loading.MapLoader;
import jsettlers.logic.map.loading.list.DirectoryMapLister;
import jsettlers.logic.player.PlayerSetting;
import jsettlers.main.JSettlersGame;
import jsettlers.main.replay.ReplayUtils;
import jsettlers.testutils.TestUtils;

/**
 * Local-only integration test for cross-water colonization (Phase 1 + Phase 2). It loads an island map by absolute filesystem path, runs an
 * {@code AI_VERY_HARD} player against a weaker across-water enemy and checks whether the AI ends up owning a finished military building on a
 * partition that is not its home partition - i.e. whether it colonized and enforced a beachhead.
 * <p>
 * The map lives outside the repository, so the test is <b>skipped</b> (not failed) via {@link org.junit.Assume} when the file is absent; it
 * therefore never runs in CI. It is intentionally verbose: it prints how far the colonization pipeline progressed each step (beachhead
 * claimed, harbor built, cargo ship, goods delivered, tower placed / finished / occupied) so a non-completing run is still a useful report.
 *
 * @author jsettlers colonization AI
 */
public class ColonizationIT {

	// A real GOG Settlers 3 island map that (unlike the resource-less demo map) loads a full resource layer AND has ore on landmasses the
	// subject cannot reach by land but can reach by sea (verified via the raw grid scan + Phase-0 sea-reachable scan below). Overridable with
	// -DcolonizationMap=<path> so a different candidate can be tried without recompiling.
	private static final File MAP_FILE = new File(
			System.getProperty("colonizationMap", "C:/Games/Settlers3GOG/Map/MULTI/640-4-island_1.map"));
	private static final int MINUTES = 1000 * 60;
	private static final int STEP_MINUTES = 10;
	// The farm reaches its first crop only around t=140; shipping that crop HOME then needs further headroom to raise a beachhead export harbor,
	// dock it, and sail a cargo ship home. The loop breaks early as soon as the colony's crop is observed en route to / arrived at home.
	private static final int TOTAL_MINUTES = 240;

	static {
		CommonConstants.ENABLE_CONSOLE_LOGGING = true;
		Constants.FOG_OF_WAR_DEFAULT_ENABLED = false;
		CommonConstants.DISABLE_ORIGINAL_MAPS_CHECKSUM = true; // the extracted original .map may not carry the S3 checksum
		CommonConstants.DETERMINISTIC_AI = true; // run the AI sequentially so this long emergent pipeline is reproducible run-to-run
		TestUtils.setupTempResourceManager();
	}

	@Test
	public void veryHardShouldColonizeAndHoldABeachhead() throws MapLoadException {
		assumeTrue("Island map not present, skipping ColonizationIT: " + MAP_FILE, MAP_FILE.exists());

		MapLoader map = MapLoader.getLoaderForListedMap(new DirectoryMapLister.ListedMapFile(MAP_FILE));
		System.out.println("[ColonizationIT.loader] loaderClass=" + map.getClass().getName() + " maxPlayers=" + map.getMaxPlayers());
		int maxPlayers = map.getMaxPlayers();
		byte subjectId = (byte) 0;

		PlayerSetting[] playerSettings = new PlayerSetting[maxPlayers];
		for (byte i = 0; i < maxPlayers; i++) {
			// subject is a very-hard AI on team 0; every other slot is a weak AI on its own team, so the subject has across-water enemies
			// (which motivates the economy to build a dockyard) but is not quickly overrun.
			EPlayerType type = (i == subjectId) ? EPlayerType.AI_VERY_HARD : EPlayerType.AI_VERY_EASY;
			playerSettings[i] = new PlayerSetting(type, ECivilisation.ROMAN, i);
		}

		JSettlersGame.GameRunner startingGame = AiTestUtils.createStartingGame(playerSettings, map);
		IStartedGame startedGame = ReplayUtils.waitForGameStartup(startingGame);
		MainGrid mainGrid = startingGame.getMainGrid();
		PartitionsGrid partitionsGrid = mainGrid.getPartitionsGrid();
		AiStatistics aiStatistics = new AiStatistics(mainGrid, Executors.newWorkStealingPool());

		boolean colonizedAndHeld = false;
		boolean builtBeachheadMine = false;
		boolean beachheadMineProducing = false;
		boolean builtBeachheadFarm = false;
		boolean beachheadFarmProducing = false;
		boolean builtExportHarbor = false; // a HARBOR raised on the beachhead to ship the farm's crop home
		boolean cropShippedHome = false;   // a cargo ship observed carrying colony crop toward the home coast (attributable proof)
		int maxHomeCrop = -1;              // high-water mark of the home partition's crop stock across the run
		for (int minute = STEP_MINUTES; minute <= TOTAL_MINUTES; minute += STEP_MINUTES) {
			MatchConstants.clock().fastForwardTo(minute * MINUTES);
			aiStatistics.updateStatistics();

			ShortPoint2D home = aiStatistics.getPositionOfPartition(subjectId);
			short homePartition = home == null ? -1 : partitionsGrid.getPartitionIdAt(home.x, home.y);

			int beachheadGround = 0;
			int foreignMilitaryUnderConstruction = 0;
			int foreignMilitaryFinished = 0;
			int foreignMilitaryOccupied = 0;
			int foreignMineUnderConstruction = 0;
			int foreignMineFinished = 0;
			int foreignMineOccupied = 0;
			int foreignFarmUnderConstruction = 0;
			int foreignFarmFinished = 0;
			int foreignFarmOccupied = 0;
			int foreignHarborUnderConstruction = 0;
			int foreignHarborFinished = 0;
			for (Building building : Building.getAllBuildings()) {
				if (building.getPlayer().playerId != subjectId) {
					continue;
				}
				ShortPoint2D pos = building.getPosition();
				boolean foreign = home != null && !aiStatistics.getMainGrid().getLandscapeGrid().isReachable(pos.x, pos.y, home.x, home.y, false);
				if (!foreign) {
					continue;
				}
				EBuildingType type = building.getBuildingVariant().getType();
				if (type.isMilitaryBuilding()) {
					if (!building.isConstructionFinished()) {
						foreignMilitaryUnderConstruction++;
					} else {
						foreignMilitaryFinished++;
						if (building.isOccupied()) {
							foreignMilitaryOccupied++;
						}
					}
				} else if (type.isMine()) {
					if (!building.isConstructionFinished()) {
						foreignMineUnderConstruction++;
					} else {
						foreignMineFinished++;
						if (building.isOccupied()) {
							foreignMineOccupied++;
						}
					}
				} else if (type == EBuildingType.FARM) {
					if (!building.isConstructionFinished()) {
						foreignFarmUnderConstruction++;
					} else {
						foreignFarmFinished++;
						if (building.isOccupied()) {
							foreignFarmOccupied++;
						}
					}
				} else if (type == EBuildingType.HARBOR) {
					// the beachhead EXPORT harbor - it ships the farm's crop HOME (the home supply harbor is on the home landmass, not foreign)
					if (!building.isConstructionFinished()) {
						foreignHarborUnderConstruction++;
					} else {
						foreignHarborFinished++;
					}
				}
			}
			// buildable ground the subject owns across water (Phase 1 beachhead) + delivered goods there
			int deliveredPlanks = 0;
			int deliveredStone = 0;
			int deliveredPicks = 0;
			int deliveredFood = 0;
			int producedOre = 0;
			int deliveredScythes = 0;
			int producedCrop = 0;
			ShortPoint2D beachheadTile = null;
			// The beachhead can span several land partitions (mountain ridges / inlets split the island), each with its own PartitionManager
			// stock. Aggregate the delivered goods + produced ore across ALL distinct foreign partitions the subject owns (deduped by partition
			// id), so the mine's own partition is always counted - not just whichever foreign tile happens to be scanned first.
			java.util.Set<Short> countedForeignPartitions = new java.util.HashSet<>();
			for (ShortPoint2D land : aiStatistics.getLandForPlayer(subjectId)) {
				if (home == null || aiStatistics.getMainGrid().getLandscapeGrid().isReachable(land.x, land.y, home.x, home.y, false)) {
					continue; // home landmass
				}
				beachheadGround++;
				if (beachheadTile == null) {
					beachheadTile = land;
				}
				short foreignPartition = partitionsGrid.getPartitionIdAt(land.x, land.y);
				if (!countedForeignPartitions.add(foreignPartition)) {
					continue; // this partition's stock already summed
				}
				jsettlers.common.map.partition.IPartitionData data = partitionsGrid.getPartitionDataForManagerAt(land.x, land.y);
				deliveredPlanks += data.getAmountOf(EMaterialType.PLANK);
				deliveredStone += data.getAmountOf(EMaterialType.STONE);
				deliveredPicks += data.getAmountOf(EMaterialType.PICK);
				deliveredFood += data.getAmountOf(EMaterialType.BREAD) + data.getAmountOf(EMaterialType.MEAT) + data.getAmountOf(EMaterialType.FISH);
				// ore produced by a working beachhead mine accumulates as offers in its (isolated) beachhead partition (nothing consumes it there
				// yet - Phase 4 would ship it home), so a positive count is direct evidence the mine actually produced.
				producedOre += data.getAmountOf(EMaterialType.COAL) + data.getAmountOf(EMaterialType.IRONORE) + data.getAmountOf(EMaterialType.GOLDORE)
						+ data.getAmountOf(EMaterialType.GEMS);
				deliveredScythes += data.getAmountOf(EMaterialType.SCYTHE);
				// crop harvested by a working beachhead farm accumulates as offers in its (isolated) beachhead partition (nothing consumes it there
				// yet - a mill/bakery or shipping it home would be a later slice), so a positive count is direct evidence the farm actually produced.
				producedCrop += data.getAmountOf(EMaterialType.CROP);
			}

			// --- SHIP-THE-CROP-HOME evidence: the colony feeds home. Two attributable signals, since only the beachhead EXPORT harbor requests CROP
			// (the home supply harbor ships build goods the other way): (a) the HOME partition's crop stock, into which the export cargo ship drops
			// the colony's crop; (b) any cargo ship actually CARRYING crop is colony crop en route to the home coast. Home crop is consumed by the
			// home mill so its stock fluctuates - the in-transit-crop signal is the clean, unambiguous proof, so we track both and the max seen.
			jsettlers.common.map.partition.IPartitionData homeData = home == null ? null : partitionsGrid.getPartitionDataForManagerAt(home.x, home.y);
			int homeCrop = homeData == null ? -1 : homeData.getAmountOf(EMaterialType.CROP);
			int homeStone = homeData == null ? -1 : homeData.getAmountOf(EMaterialType.STONE); // is the home economy stone-starved (why the export harbor may not finish)?
			int cargoShipsCarryingCrop = 0;
			int cropInTransit = 0;
			for (ShortPoint2D shipPos : aiStatistics.getPositionsOfMovablesWithTypeForPlayer(subjectId, jsettlers.common.movable.EMovableType.CARGO_SHIP)) {
				jsettlers.common.movable.IGraphicsMovable mov = mainGrid.getMovableGrid().getMovableAt(shipPos.x, shipPos.y);
				if (!(mov instanceof jsettlers.common.movable.IGraphicsCargoShip)) {
					continue;
				}
				jsettlers.common.movable.IGraphicsCargoShip ship = (jsettlers.common.movable.IGraphicsCargoShip) mov;
				int shipCrop = 0;
				for (int s = 0; s < ship.getNumberOfCargoStacks(); s++) {
					if (ship.getCargoType(s) == EMaterialType.CROP) {
						shipCrop += ship.getCargoCount(s);
					}
				}
				if (shipCrop > 0) {
					cargoShipsCarryingCrop++;
					cropInTransit += shipCrop;
				}
			}
			if (cropInTransit > 0) {
				cropShippedHome = true; // a cargo ship is carrying colony crop toward home - unambiguous proof the export line works
			}
			if (homeCrop > maxHomeCrop) {
				maxHomeCrop = homeCrop;
			}

			int dockyards = aiStatistics.getNumberOfBuildingTypeForPlayer(EBuildingType.DOCKYARD, subjectId);
			int harbors = aiStatistics.getTotalNumberOfBuildingTypeForPlayer(EBuildingType.HARBOR, subjectId);
			int ferries = aiStatistics.getPositionsOfMovablesWithTypeForPlayer(subjectId, jsettlers.common.movable.EMovableType.FERRY).size();
			int cargoShips = aiStatistics.getPositionsOfMovablesWithTypeForPlayer(subjectId, jsettlers.common.movable.EMovableType.CARGO_SHIP).size();

			System.out.printf(
					"[ColonizationIT] t=%3dmin homePart=%d | dockyards=%d harbors=%d ferries=%d cargoShips=%d | beachheadGround=%d deliveredPlanks=%d deliveredStone=%d | foreignTower(building=%d finished=%d occupied=%d)%n",
					minute, homePartition, dockyards, harbors, ferries, cargoShips, beachheadGround, deliveredPlanks, deliveredStone,
					foreignMilitaryUnderConstruction, foreignMilitaryFinished, foreignMilitaryOccupied);
			System.out.printf(
					"[ColonizationIT.mine] t=%3dmin | foreignMine(building=%d finished=%d occupied=%d) | deliveredPicks=%d deliveredFood=%d producedOre=%d%n",
					minute, foreignMineUnderConstruction, foreignMineFinished, foreignMineOccupied, deliveredPicks, deliveredFood, producedOre);
			System.out.printf(
					"[ColonizationIT.farm] t=%3dmin | foreignFarm(building=%d finished=%d occupied=%d) | deliveredScythes=%d producedCrop=%d%n",
					minute, foreignFarmUnderConstruction, foreignFarmFinished, foreignFarmOccupied, deliveredScythes, producedCrop);
			System.out.printf(
					"[ColonizationIT.export] t=%3dmin | beachheadHarbor(building=%d finished=%d) | homeCrop=%d maxHomeCrop=%d homeStone=%d | cargoShipsCarryingCrop=%d cropInTransit=%d%n",
					minute, foreignHarborUnderConstruction, foreignHarborFinished, homeCrop, maxHomeCrop, homeStone, cargoShipsCarryingCrop, cropInTransit);

			if (foreignHarborUnderConstruction + foreignHarborFinished > 0) {
				builtExportHarbor = true;
			}

			if (foreignMineUnderConstruction + foreignMineFinished > 0) {
				builtBeachheadMine = true;
			}
			if (producedOre > 0) {
				beachheadMineProducing = true;
			}
			if (foreignFarmUnderConstruction + foreignFarmFinished > 0) {
				builtBeachheadFarm = true;
			}
			if (producedCrop > 0) {
				beachheadFarmProducing = true;
			}

			// --- trigger/scan diagnostics: why is (or isn't) colonization firing? ---
			int borderIngestible = aiStatistics.getBorderIngestibleByPioneersOf(subjectId).size();
			int joblessBearers = aiStatistics.getPositionsOfJoblessBearersForPlayer(subjectId).size();
			int seaCoal = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.COAL).size();
			int seaIron = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.IRONORE).size();
			int seaGold = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.GOLDORE).size();
			System.out.printf(
					"[ColonizationIT.diag] t=%3dmin | borderIngestible=%d joblessBearers=%d | seaReachableTargets(coal=%d iron=%d gold=%d)%n",
					minute, borderIngestible, joblessBearers, seaCoal, seaIron, seaGold);
			// --- FARM-FEASIBILITY geography scan: group ALL owned foreign tiles by TERRITORY partition and report, per partition, its size, how
			// much flat grass/earth it holds, whether it touches the sea (coast-suppliable), and the best farm footprint it can host (constructable
			// origin + plantable work-area tiles). Answers: is there a coast-suppliable flat-grass area big enough for a farm anywhere on the beachhead?
			if (minute == 90 || minute == 150) {
				LandscapeGrid lg2 = mainGrid.getLandscapeGrid();
				AbstractConstructionMarkableMap cg2 = mainGrid.getConstructionMarksGrid();
				// the subject's dock water = the sea the cargo ship / ferry sails, so we can test whether a partition's coast is reachable by ship.
				ShortPoint2D dockWater = null;
				for (Building b : Building.getAllBuildings()) {
					if (b.getPlayer().playerId == subjectId && b instanceof jsettlers.logic.buildings.workers.DockyardBuilding) {
						jsettlers.logic.DockPosition dock = ((jsettlers.logic.buildings.workers.DockyardBuilding) b).getDock();
						if (dock != null) {
							dockWater = dock.getWaterPosition();
						}
					}
				}
				final short ferrySea2 = dockWater != null ? lg2.getBlockedPartitionAt(dockWater.x, dockWater.y) : 0;
				java.util.Map<Integer, int[]> byPart = new java.util.HashMap<>(); // partition -> [size, grassEarth, coastTiles, farmConstructable, bestPlantable, ferrySeaCoastTiles, shipReachableCoastTiles]
				for (short x = 0; x < mainGrid.getWidth(); x++) {
					for (short y = 0; y < mainGrid.getHeight(); y++) {
						if (partitionsGrid.getPlayerIdAt(x, y) != subjectId) {
							continue;
						}
						if (home != null && lg2.isReachable(x, y, home.x, home.y, false)) {
							continue; // home landmass
						}
						int part = partitionsGrid.getPartitionIdAt(x, y);
						int[] s = byPart.computeIfAbsent(part, k -> new int[7]);
						s[0]++;
						ELandscapeType lt = lg2.getLandscapeTypeAt(x, y);
						boolean flat = lt == ELandscapeType.GRASS || lt == ELandscapeType.EARTH;
						if (flat) {
							s[1]++;
						}
						boolean coast = false;
						for (jsettlers.common.movable.EDirection d : jsettlers.common.movable.EDirection.VALUES) {
							int wx = x + d.gridDeltaX, wy = y + d.gridDeltaY;
							if (mainGrid.isInBounds(wx, wy) && lg2.getLandscapeTypeAt(wx, wy).isWater) {
								coast = true;
								if (dockWater != null && lg2.getBlockedPartitionAt(wx, wy) == ferrySea2) {
									s[5]++; // this owned coast tile borders the ferry sea
									if (lg2.isReachable(dockWater.x, dockWater.y, wx, wy, true)) {
										s[6]++; // ...and a cargo ship can actually path there from the dock
									}
								}
							}
						}
						if (coast) {
							s[2]++;
						}
						if (flat && cg2.canConstructAt(x, y, EBuildingType.FARM, subjectId)) {
							s[3]++;
							int plantable = 0; // ROMAN farm: workcenter (2,10), workradius 6
							for (int dx = -6; dx <= 6; dx++) {
								for (int dy = -6; dy <= 6; dy++) {
									if (Math.sqrt(dx * dx + dy * dy) > 6) {
										continue;
									}
									int px = x + 2 + dx, py = y + 10 + dy;
									if (px >= 0 && py >= 0 && px < mainGrid.getWidth() && py < mainGrid.getHeight()
											&& partitionsGrid.getPlayerIdAt(px, py) == subjectId
											&& mainGrid.isCornPlantable(new ShortPoint2D(px, py))) {
										plantable++;
									}
								}
							}
							if (plantable > s[4]) {
								s[4] = plantable;
							}
						}
					}
				}
				final int m = minute;
				byPart.entrySet().stream()
						.filter(e -> e.getValue()[0] >= 20)
						.sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
						.limit(8)
						.forEach(e -> System.out.printf(
								"[ColonizationIT.farmScan] t=%3dmin part=%d size=%d grassEarth=%d coastTiles=%d farmConstructable=%d bestPlantable=%d ferrySeaCoast=%d shipReachableCoast=%d%n",
								m, e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3], e.getValue()[4], e.getValue()[5], e.getValue()[6]));
				System.out.printf("[ColonizationIT.farmScan] t=%3dmin ferrySea=%d%n", m, ferrySea2);
			}
			if (minute == 60 || minute == 120) {
				int gridIron = 0, gridCoal = 0, gridGold = 0, gridFish = 0, gridAny = 0;
				for (short gx = 0; gx < mainGrid.getWidth(); gx++) {
					for (short gy = 0; gy < mainGrid.getHeight(); gy++) {
						if (mainGrid.getLandscapeGrid().getResourceAmountAt(gx, gy) > 0) {
							gridAny++;
							switch (mainGrid.getLandscapeGrid().getResourceTypeAt(gx, gy)) {
							case IRONORE: gridIron++; break;
							case COAL: gridCoal++; break;
							case GOLDORE: gridGold++; break;
							case FISH: gridFish++; break;
							default: break;
							}
						}
					}
				}
				System.out.printf("[ColonizationIT.grid] t=%dmin rawResourceTiles any=%d iron=%d coal=%d gold=%d fish=%d%n",
						minute, gridAny, gridIron, gridCoal, gridGold, gridFish);
			}

			// --- Phase 3 mine-placement diagnostics: once the beachhead is held, is there OWNED mountain-with-ore a mine can sit on? ---
			if (beachheadTile != null) {
				LandscapeGrid lg = mainGrid.getLandscapeGrid();
				AbstractConstructionMarkableMap cg = mainGrid.getConstructionMarksGrid();
				int ownMountain = 0, ownOre = 0, ownMineBuildable = 0;
				for (ShortPoint2D land : aiStatistics.getLandForPlayer(subjectId)) {
					if (home != null && lg.isReachable(land.x, land.y, home.x, home.y, false)) {
						continue; // home landmass, not the beachhead
					}
					ELandscapeType lt = lg.getLandscapeTypeAt(land.x, land.y);
					if (lt == ELandscapeType.MOUNTAIN || lt == ELandscapeType.MOUNTAINBORDER) {
						ownMountain++;
					}
					if (lg.getResourceAmountAt(land.x, land.y) > 0) {
						ownOre++;
					}
					if (cg.canConstructAt(land.x, land.y, EBuildingType.IRONMINE, subjectId)
							|| cg.canConstructAt(land.x, land.y, EBuildingType.COALMINE, subjectId)
							|| cg.canConstructAt(land.x, land.y, EBuildingType.GOLDMINE, subjectId)) {
						ownMineBuildable++;
					}
				}
				// where is the ore we colonized FOR, relative to the beachhead we developed?
				ShortPoint2D ore = null;
				String oreType = "none";
				java.util.List<ShortPoint2D> gold = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.GOLDORE);
				java.util.List<ShortPoint2D> coal = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.COAL);
				java.util.List<ShortPoint2D> iron = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.IRONORE);
				if (!gold.isEmpty()) { ore = gold.get(0); oreType = "gold"; }
				else if (!coal.isEmpty()) { ore = coal.get(0); oreType = "coal"; }
				else if (!iron.isEmpty()) { ore = iron.get(0); oreType = "iron"; }
				if (ore != null) {
					boolean oreOwned = partitionsGrid.getPlayerIdAt(ore.x, ore.y) == subjectId;
					int ownedNearOre = 0;
					for (int dx = -20; dx <= 20; dx++) {
						for (int dy = -20; dy <= 20; dy++) {
							int x = ore.x + dx, y = ore.y + dy;
							if (x >= 0 && y >= 0 && x < mainGrid.getWidth() && y < mainGrid.getHeight() && partitionsGrid.getPlayerIdAt(x, y) == subjectId) {
								ownedNearOre++;
							}
						}
					}
					int pioneersNearOre = 0;
					for (ShortPoint2D p : aiStatistics.getPositionsOfMovablesWithTypeForPlayer(subjectId, jsettlers.common.movable.EMovableType.PIONEER)) {
						if (p.getOnGridDistTo(ore) <= 20) {
							pioneersNearOre++;
						}
					}
					System.out.printf("[ColonizationIT.mineDiag] t=%3dmin | ownForeign(mountain=%d ore=%d mineBuildable=%d) | oreTarget(%s @%d,%d owned=%b ownedWithin20=%d pioneersWithin20=%d distToBeachheadTile=%d)%n",
							minute, ownMountain, ownOre, ownMineBuildable, oreType, ore.x, ore.y, oreOwned, ownedNearOre, pioneersNearOre, ore.getOnGridDistTo(beachheadTile));
				} else {
					System.out.printf("[ColonizationIT.mineDiag] t=%3dmin | ownForeign(mountain=%d ore=%d mineBuildable=%d) | oreTarget=NONE%n",
							minute, ownMountain, ownOre, ownMineBuildable);
				}
			}

			if (foreignMilitaryOccupied > 0) {
				colonizedAndHeld = true;
			}
			// stop as soon as the whole pipeline is demonstrably complete: a beachhead farm that has produced crop AND that crop has been shipped
			// HOME (the colony feeds the home economy - this slice's goal), or - the legacy mine outcome - a beachhead mine that has produced ore.
			if ((builtBeachheadFarm && beachheadFarmProducing && cropShippedHome) || (builtBeachheadMine && beachheadMineProducing)) {
				break;
			}
		}

		ReplayUtils.awaitShutdown(startedGame);
		// Prerequisite (Phase 1 + 2): the AI must have colonized and enforced a beachhead - a finished, occupied military building across water.
		Assert.assertTrue(
				"AI_VERY_HARD did not end up owning a finished, occupied military building on a foreign (across-water) partition within "
						+ TOTAL_MINUTES + " minutes. See the per-step [ColonizationIT] log above for how far the pipeline progressed.",
				colonizedAndHeld);
		// Economy-pivot goal: on the held beachhead the AI must raise a FARM (gated on the occupied tower, so this implies the beachhead was
		// held) and it must actually PRODUCE crop - the achievable overseas producer, since the reachable ore is landlocked and a mine there
		// can never be supplied (see the 2026-07-28 engine learning). Both are logged/broken on above via [ColonizationIT.farm].
		Assert.assertTrue(
				"AI_VERY_HARD held a beachhead but never built a FARM on it within " + TOTAL_MINUTES
						+ " minutes. See the per-step [ColonizationIT.farm] log above (farm built / finished / occupied / producedCrop).",
				builtBeachheadFarm);
		Assert.assertTrue(
				"AI_VERY_HARD built a beachhead farm but it never produced any crop within " + TOTAL_MINUTES
						+ " minutes (not manned by a farmer, or no plantable field). See the per-step [ColonizationIT.farm] log above.",
				beachheadFarmProducing);
		// This slice's goal - make the crop USEFUL by shipping it HOME: the AI must raise a HARBOR on the beachhead and run the sea-trade line in
		// reverse so the farm's crop is delivered to the home partition (the colony feeds home). Both are logged/broken on above via [ColonizationIT.export].
		Assert.assertTrue(
				"AI_VERY_HARD produced crop on the beachhead farm but never raised a beachhead EXPORT harbor to ship it home within "
						+ TOTAL_MINUTES + " minutes. See the per-step [ColonizationIT.export] log above (beachheadHarbor building / finished).",
				builtExportHarbor);
		Assert.assertTrue(
				"AI_VERY_HARD raised a beachhead export harbor but no cargo ship was ever observed carrying the colony's crop toward the home coast "
						+ "within " + TOTAL_MINUTES + " minutes (crop never shipped home). See the per-step [ColonizationIT.export] log above "
						+ "(cargoShipsCarryingCrop / cropInTransit / homeCrop).",
				cropShippedHome);
		System.out.printf("[ColonizationIT] RESULT colonizedAndHeld=%b builtBeachheadFarm=%b beachheadFarmProducing=%b builtExportHarbor=%b cropShippedHome=%b maxHomeCrop=%d builtBeachheadMine=%b beachheadMineProducing=%b%n",
				colonizedAndHeld, builtBeachheadFarm, beachheadFarmProducing, builtExportHarbor, cropShippedHome, maxHomeCrop, builtBeachheadMine, beachheadMineProducing);
	}
}
