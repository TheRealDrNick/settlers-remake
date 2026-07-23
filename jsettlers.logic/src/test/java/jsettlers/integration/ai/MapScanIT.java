/*******************************************************************************
 * TEMP colonization verification helper - not for merge.
 *
 * A fast map-selection probe: point it at an original Settlers 3 .map file via -DscanMap=<path>, it starts a game with slot 0 as an
 * AI_VERY_HARD subject, fast-forwards a few minutes so the subject has a home partition and coast, and then prints how many resource
 * tiles the map actually carries and whether any ore is sea-reachable (off the subject's landmass but reachable by ship). Use it to
 * find a map that satisfies the colonization preconditions before running the full ColonizationIT.
 *******************************************************************************/
package jsettlers.integration.ai;

import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.concurrent.Executors;

import org.junit.Test;

import jsettlers.ai.highlevel.AiStatistics;
import jsettlers.common.CommonConstants;
import jsettlers.common.ai.EPlayerType;
import jsettlers.common.landscape.EResourceType;
import jsettlers.common.menu.IStartedGame;
import jsettlers.common.player.ECivilisation;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.constants.Constants;
import jsettlers.logic.constants.MatchConstants;
import jsettlers.logic.map.grid.MainGrid;
import jsettlers.logic.map.loading.MapLoadException;
import jsettlers.logic.map.loading.MapLoader;
import jsettlers.logic.map.loading.list.DirectoryMapLister;
import jsettlers.logic.player.PlayerSetting;
import jsettlers.main.JSettlersGame;
import jsettlers.main.replay.ReplayUtils;
import jsettlers.testutils.TestUtils;

public class MapScanIT {

	private static final int MINUTES = 1000 * 60;
	private static final int WARMUP_MINUTES = 5;

	static {
		CommonConstants.ENABLE_CONSOLE_LOGGING = true;
		Constants.FOG_OF_WAR_DEFAULT_ENABLED = false;
		CommonConstants.DISABLE_ORIGINAL_MAPS_CHECKSUM = true;
		TestUtils.setupTempResourceManager();
	}

	@Test
	public void scanMapForColonizationPreconditions() throws MapLoadException {
		String path = System.getProperty("scanMap");
		assumeTrue("no -DscanMap given, skipping MapScanIT", path != null && !path.isEmpty());
		File mapFile = new File(path);
		assumeTrue("scanMap file not found: " + mapFile, mapFile.exists());

		MapLoader map = MapLoader.getLoaderForListedMap(new DirectoryMapLister.ListedMapFile(mapFile));
		int maxPlayers = map.getMaxPlayers();
		System.out.println("[MapScan] file=" + mapFile.getName() + " loaderClass=" + map.getClass().getSimpleName() + " maxPlayers=" + maxPlayers);

		byte subjectId = (byte) 0;
		PlayerSetting[] playerSettings = new PlayerSetting[maxPlayers];
		for (byte i = 0; i < maxPlayers; i++) {
			EPlayerType type = (i == subjectId) ? EPlayerType.AI_VERY_HARD : EPlayerType.AI_VERY_EASY;
			playerSettings[i] = new PlayerSetting(type, ECivilisation.ROMAN, i);
		}

		JSettlersGame.GameRunner startingGame = AiTestUtils.createStartingGame(playerSettings, map);
		IStartedGame startedGame = ReplayUtils.waitForGameStartup(startingGame);
		MainGrid mainGrid = startingGame.getMainGrid();
		AiStatistics aiStatistics = new AiStatistics(mainGrid, Executors.newWorkStealingPool());

		MatchConstants.clock().fastForwardTo(WARMUP_MINUTES * MINUTES);
		aiStatistics.updateStatistics();

		// raw resource layer census
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
		System.out.printf("[MapScan.grid] file=%s rawResourceTiles any=%d iron=%d coal=%d gold=%d fish=%d%n",
				mapFile.getName(), gridAny, gridIron, gridCoal, gridGold, gridFish);

		ShortPoint2D home = aiStatistics.getPositionOfPartition(subjectId);
		int seaCoal = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.COAL).size();
		int seaIron = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.IRONORE).size();
		int seaGold = aiStatistics.getSeaReachableResourceTargets(subjectId, EResourceType.GOLDORE).size();
		System.out.printf("[MapScan.sea] file=%s home=%s | seaReachable(coal=%d iron=%d gold=%d)%n",
				mapFile.getName(), home, seaCoal, seaIron, seaGold);
		System.out.println("[MapScan.scan] " + aiStatistics.debugColonizationScan(subjectId, EResourceType.IRONORE));
		System.out.println("[MapScan.scan] " + aiStatistics.debugColonizationScan(subjectId, EResourceType.COAL));
		System.out.println("[MapScan.scan] " + aiStatistics.debugColonizationScan(subjectId, EResourceType.GOLDORE));

		// dockyard-trigger diagnostics: why does the economy (not) build a dockyard? (BuildingListEconomyMinister.addNavalBuildings)
		jsettlers.logic.player.Player subject = mainGrid.getPartitionsGrid().getPlayer(subjectId);
		boolean enemyAcrossWater = aiStatistics.hasEnemyAcrossWaterOf(subject);
		boolean flankable = aiStatistics.hasFlankableEnemyOf(subject);
		StringBuilder enemyInfo = new StringBuilder();
		for (jsettlers.common.player.IPlayer enemy : aiStatistics.getAliveEnemiesOf(subject)) {
			boolean landReach = aiStatistics.isEnemyReachableByLand(subjectId, enemy);
			enemyInfo.append(" enemy#").append(enemy.getPlayerId()).append("(landReachable=").append(landReach).append(")");
		}
		System.out.printf("[MapScan.dock] hasEnemyAcrossWater=%b hasFlankableEnemy=%b |%s%n", enemyAcrossWater, flankable, enemyInfo);

		ReplayUtils.awaitShutdown(startedGame);
	}
}
