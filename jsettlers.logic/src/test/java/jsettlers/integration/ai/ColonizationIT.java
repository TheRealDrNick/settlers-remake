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
	private static final int TOTAL_MINUTES = 120;

	static {
		CommonConstants.ENABLE_CONSOLE_LOGGING = true;
		Constants.FOG_OF_WAR_DEFAULT_ENABLED = false;
		CommonConstants.DISABLE_ORIGINAL_MAPS_CHECKSUM = true; // the extracted original .map may not carry the S3 checksum
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
		for (int minute = STEP_MINUTES; minute <= TOTAL_MINUTES; minute += STEP_MINUTES) {
			MatchConstants.clock().fastForwardTo(minute * MINUTES);
			aiStatistics.updateStatistics();

			ShortPoint2D home = aiStatistics.getPositionOfPartition(subjectId);
			short homePartition = home == null ? -1 : partitionsGrid.getPartitionIdAt(home.x, home.y);

			int beachheadGround = 0;
			int foreignMilitaryUnderConstruction = 0;
			int foreignMilitaryFinished = 0;
			int foreignMilitaryOccupied = 0;
			for (Building building : Building.getAllBuildings()) {
				if (building.getPlayer().playerId != subjectId) {
					continue;
				}
				ShortPoint2D pos = building.getPosition();
				boolean foreign = home != null && !aiStatistics.getMainGrid().getLandscapeGrid().isReachable(pos.x, pos.y, home.x, home.y, false);
				if (foreign && building.getBuildingVariant().getType().isMilitaryBuilding()) {
					if (!building.isConstructionFinished()) {
						foreignMilitaryUnderConstruction++;
					} else {
						foreignMilitaryFinished++;
						if (building.isOccupied()) {
							foreignMilitaryOccupied++;
						}
					}
				}
			}
			// buildable ground the subject owns across water (Phase 1 beachhead) + delivered goods there
			int deliveredPlanks = 0;
			int deliveredStone = 0;
			ShortPoint2D beachheadTile = null;
			for (ShortPoint2D land : aiStatistics.getLandForPlayer(subjectId)) {
				if (home != null && !aiStatistics.getMainGrid().getLandscapeGrid().isReachable(land.x, land.y, home.x, home.y, false)) {
					beachheadGround++;
					if (beachheadTile == null) {
						beachheadTile = land;
					}
				}
			}
			if (beachheadTile != null) {
				deliveredPlanks = partitionsGrid.getPartitionDataForManagerAt(beachheadTile.x, beachheadTile.y).getAmountOf(EMaterialType.PLANK);
				deliveredStone = partitionsGrid.getPartitionDataForManagerAt(beachheadTile.x, beachheadTile.y).getAmountOf(EMaterialType.STONE);
			}

			int dockyards = aiStatistics.getNumberOfBuildingTypeForPlayer(EBuildingType.DOCKYARD, subjectId);
			int harbors = aiStatistics.getTotalNumberOfBuildingTypeForPlayer(EBuildingType.HARBOR, subjectId);
			int ferries = aiStatistics.getPositionsOfMovablesWithTypeForPlayer(subjectId, jsettlers.common.movable.EMovableType.FERRY).size();
			int cargoShips = aiStatistics.getPositionsOfMovablesWithTypeForPlayer(subjectId, jsettlers.common.movable.EMovableType.CARGO_SHIP).size();

			System.out.printf(
					"[ColonizationIT] t=%3dmin homePart=%d | dockyards=%d harbors=%d ferries=%d cargoShips=%d | beachheadGround=%d deliveredPlanks=%d deliveredStone=%d | foreignTower(building=%d finished=%d occupied=%d)%n",
					minute, homePartition, dockyards, harbors, ferries, cargoShips, beachheadGround, deliveredPlanks, deliveredStone,
					foreignMilitaryUnderConstruction, foreignMilitaryFinished, foreignMilitaryOccupied);

			// --- trigger/scan diagnostics: why is (or isn't) colonization firing? ---
			int borderIngestible = aiStatistics.getBorderIngestibleByPioneersOf(subjectId).size();
			int joblessBearers = aiStatistics.getPositionsOfJoblessBearersForPlayer(subjectId).size();
			int seaCoal = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.COAL).size();
			int seaIron = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.IRONORE).size();
			int seaGold = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.GOLDORE).size();
			System.out.printf(
					"[ColonizationIT.diag] t=%3dmin | borderIngestible=%d joblessBearers=%d | seaReachableTargets(coal=%d iron=%d gold=%d)%n",
					minute, borderIngestible, joblessBearers, seaCoal, seaIron, seaGold);
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
				System.out.println("[ColonizationIT.scan] " + aiStatistics.debugColonizationScan(subjectId, EResourceType.IRONORE));
				System.out.println("[ColonizationIT.scan] " + aiStatistics.debugColonizationScan(subjectId, EResourceType.COAL));
				System.out.println("[ColonizationIT.scan] " + aiStatistics.debugColonizationScan(subjectId, EResourceType.GOLDORE));
				System.out.println("[ColonizationIT.scan] " + aiStatistics.debugColonizationScan(subjectId, EResourceType.FISH));
			}

			if (foreignMilitaryOccupied > 0) {
				colonizedAndHeld = true;
				break;
			}
		}

		ReplayUtils.awaitShutdown(startedGame);
		Assert.assertTrue(
				"AI_VERY_HARD did not end up owning a finished, occupied military building on a foreign (across-water) partition within "
						+ TOTAL_MINUTES + " minutes. See the per-step [ColonizationIT] log above for how far the pipeline progressed.",
				colonizedAndHeld);
	}
}
