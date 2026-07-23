package jsettlers.integration.ai;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import jsettlers.common.CommonConstants;
import jsettlers.common.ai.EPlayerType;
import jsettlers.common.mapobject.EMapObjectType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.menu.IStartedGame;
import jsettlers.common.player.ECivilisation;
import jsettlers.common.player.IPlayer;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.buildings.military.occupying.OccupyingBuilding;
import jsettlers.logic.constants.Constants;
import jsettlers.logic.constants.MatchConstants;
import jsettlers.logic.map.grid.MainGrid;
import jsettlers.logic.map.loading.EMapStartResources;
import jsettlers.logic.map.loading.MapLoadException;
import jsettlers.logic.movable.MovableManager;
import jsettlers.logic.movable.interfaces.IAttackableMovable;
import jsettlers.logic.movable.interfaces.ILogicMovable;
import jsettlers.logic.movable.interfaces.IAttackable;
import jsettlers.logic.player.EPeaceTime;
import jsettlers.logic.player.InitialGameState;
import jsettlers.logic.player.PlayerSetting;
import jsettlers.main.JSettlersGame;
import jsettlers.main.replay.ReplayUtils;
import jsettlers.network.client.OfflineNetworkConnector;
import jsettlers.testutils.TestUtils;
import jsettlers.testutils.map.MapUtils;

/**
 * Verifies the peacetime match option: while the peacetime of a match is running, no combat damage can be dealt and no military building
 * can be captured; once it is over, combat works exactly as usual. Also verifies that a match created without peacetime (the default) is
 * combat-enabled from the very start.
 */
public class PeaceTimeIT {

	static {
		CommonConstants.ENABLE_CONSOLE_LOGGING = true;
		Constants.FOG_OF_WAR_DEFAULT_ENABLED = false;

		TestUtils.setupTempResourceManager();
	}

	private static final int MINUTES = 1000 * 60;
	private static final byte PLAYER_A = 7;
	private static final byte PLAYER_B = 9;

	@Test
	public void peaceTimeBlocksAllCombatHarmUntilItIsOver() throws MapLoadException {
		JSettlersGame.GameRunner startingGame = startTwoAiGame(EPeaceTime.MINUTES_5);
		IStartedGame startedGame = ReplayUtils.waitForGameStartup(startingGame);
		MainGrid mainGrid = startingGame.getMainGrid();
		IPlayer attackingPlayer = mainGrid.getPartitionsGrid().getPlayer(PLAYER_B);

		assertTrue("peacetime must be active right after the game start", MatchConstants.isPeaceTime());

		// snapshot all soldiers and building owners present at the start of the match. Soldiers only die to combat damage, so they must
		// all still be alive while the peacetime is running. (Civilians are excluded because they can be legitimately replaced by
		// conversions, e.g. a bearer becoming a digger, which has nothing to do with combat.)
		List<ILogicMovable> soldiersAtStart = new ArrayList<>();
		for (ILogicMovable movable : MovableManager.getAllMovables()) {
			if (EMovableType.SOLDIERS.contains(movable.getMovableType())) {
				soldiersAtStart.add(movable);
			}
		}
		assertFalse("expected pre-placed soldiers on the test map", soldiersAtStart.isEmpty());
		List<Building> buildingsAtStart = new ArrayList<>(Building.getAllBuildings());
		List<Byte> buildingOwnersAtStart = new ArrayList<>();
		for (Building building : buildingsAtStart) {
			buildingOwnersAtStart.add(building.getPlayer().getPlayerId());
		}

		MatchConstants.clock().fastForwardTo(2 * MINUTES);
		assertTrue("peacetime must still be active after 2 minutes", MatchConstants.isPeaceTime());

		// (1) combat damage against movables must be fully ignored during peacetime
		IAttackableMovable victim = findAttackableMovableOfPlayer(PLAYER_A);
		float healthBefore = victim.getHealth();
		victim.receiveHit(healthBefore * 10f, victim.getPosition(), attackingPlayer);
		assertTrue("movable must survive a lethal hit during peacetime", victim.isAlive());
		assertEquals("movable must not take any damage during peacetime", healthBefore, victim.getHealth(), 0.0001f);

		// (2) military buildings must neither be damaged nor captured during peacetime
		OccupyingBuilding tower = findOccupyingBuilding();
		byte towerOwnerBefore = tower.getPlayer().getPlayerId();
		IAttackable attackableTower = (IAttackable) mainGrid.getObjectsGrid()
				.getMapObjectAt(tower.getDoor().x, tower.getDoor().y, EMapObjectType.ATTACKABLE_TOWER);
		assertNotNull("expected an attackable tower map object at the tower door", attackableTower);
		for (int i = 0; i < 20; i++) { // enough hits to break any door if the hit was not ignored
			attackableTower.receiveHit(1000f, tower.getDoor(), attackingPlayer);
		}
		assertFalse("military building must not be destroyed during peacetime", tower.isDestroyed());
		assertEquals("military building must not be captured during peacetime", towerOwnerBefore, tower.getPlayer().getPlayerId());

		// (3) nobody may have suffered combat losses while the peacetime is running (AIs are running the whole time)
		MatchConstants.clock().fastForwardTo(4 * MINUTES + 30 * 1000);
		assertTrue(MatchConstants.isPeaceTime());
		for (ILogicMovable soldier : soldiersAtStart) {
			assertTrue("no soldier may be killed during peacetime", soldier.isAlive());
		}
		for (int i = 0; i < buildingsAtStart.size(); i++) {
			assertEquals("no building may change its owner during peacetime",
					(byte) buildingOwnersAtStart.get(i), buildingsAtStart.get(i).getPlayer().getPlayerId());
		}

		// (4) when the peacetime is over, war works exactly as usual
		MatchConstants.clock().fastForwardTo(5 * MINUTES + 5 * 1000);
		assertFalse("peacetime must be over after 5 minutes", MatchConstants.isPeaceTime());
		IAttackableMovable postWarVictim = findAttackableMovableOfPlayer(PLAYER_A);
		float postWarHealthBefore = postWarVictim.getHealth();
		postWarVictim.receiveHit(0.05f, postWarVictim.getPosition(), attackingPlayer);
		assertTrue("hit after the peacetime must reduce the health again", postWarVictim.getHealth() < postWarHealthBefore);

		ReplayUtils.awaitShutdown(startedGame);
	}

	@Test
	public void withoutPeaceTimeCombatWorksFromTheVeryStart() throws MapLoadException {
		JSettlersGame.GameRunner startingGame = startTwoAiGame(EPeaceTime.WITHOUT);
		IStartedGame startedGame = ReplayUtils.waitForGameStartup(startingGame);
		IPlayer attackingPlayer = startingGame.getMainGrid().getPartitionsGrid().getPlayer(PLAYER_B);

		assertFalse("a match without peacetime must never report peacetime", MatchConstants.isPeaceTime());

		IAttackableMovable victim = findAttackableMovableOfPlayer(PLAYER_A);
		float healthBefore = victim.getHealth();
		victim.receiveHit(0.05f, victim.getPosition(), attackingPlayer);
		assertTrue("without peacetime a hit must deal damage right away", victim.getHealth() < healthBefore);

		ReplayUtils.awaitShutdown(startedGame);
	}

	private static JSettlersGame.GameRunner startTwoAiGame(EPeaceTime peaceTime) throws MapLoadException {
		PlayerSetting[] playerSettings = AiTestUtils.getDefaultPlayerSettings(12);
		playerSettings[PLAYER_A] = new PlayerSetting(EPlayerType.AI_HARD, ECivilisation.ROMAN, (byte) 0);
		playerSettings[PLAYER_B] = new PlayerSetting(EPlayerType.AI_HARD, ECivilisation.ROMAN, (byte) 1);

		InitialGameState initialGameState = new InitialGameState(PLAYER_A, playerSettings, 1L, EMapStartResources.HIGH_GOODS, peaceTime);
		JSettlersGame game = new JSettlersGame(MapUtils.getSpezialSumpf(), new OfflineNetworkConnector(), initialGameState);
		return (JSettlersGame.GameRunner) game.start();
	}

	private static IAttackableMovable findAttackableMovableOfPlayer(byte playerId) {
		for (ILogicMovable movable : MovableManager.getAllMovables()) {
			if (movable.isAlive() && movable.getPlayer().getPlayerId() == playerId && movable instanceof IAttackableMovable) {
				return (IAttackableMovable) movable;
			}
		}
		throw new AssertionError("no attackable movable found for player " + playerId);
	}

	private static OccupyingBuilding findOccupyingBuilding() {
		for (Building building : Building.getAllBuildings()) {
			if (building instanceof OccupyingBuilding && building.isConstructionFinished() && !building.isDestroyed()) {
				return (OccupyingBuilding) building;
			}
		}
		throw new AssertionError("no finished military building found on the map");
	}
}
