/*******************************************************************************
 * Copyright (c) 2015, 2016
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
package jsettlers.logic.constants;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import jsettlers.logic.movable.civilian.BuildingWorkerMovable;
import jsettlers.network.client.interfaces.IGameClock;

/**
 * 
 * @author Andreas Eberle
 * 
 */
public final class MatchConstants {
	/**
	 * if true, the user will be able to see other players people and buildings
	 */
	public static boolean ENABLE_ALL_PLAYER_FOG_OF_WAR = false;

	/**
	 * if true, the user will be able to select other player's people and buildings.
	 */
	public static boolean ENABLE_ALL_PLAYER_SELECTION = false;

	public static boolean ENABLE_FOG_OF_WAR_DISABLING = false;

	public static final boolean ENABLE_PRODUCTION_LOG = false;

	/**
	 * NOTE: this value has only an effect if it's changed before the MainGrid is created! IT MUSTN'T BE CHANGED AFTER A MAIN GRID HAS BEEN CREATED <br>
	 * if false, no debug coloring is possible (but saves memory) <br>
	 * if true, debug coloring is possible.
	 */
	public static boolean ENABLE_DEBUG_COLORS = true;

	private MatchConstants() {
	}

	private static IGameClock clock;
	private static ExtendedRandom gameRandom;
	private static ExtendedRandom aiRandom;
	/**
	 * The game time in milliseconds at which the peacetime of the current match ends. 0 means the match is played without a peacetime, so
	 * all checks short-circuit and behave exactly as before peacetime existed. This is part of the deterministic match state: it is
	 * initialized from the {@link jsettlers.logic.player.InitialGameState} of the match (identical for all multiplayer participants and
	 * replays) and (de)serialized with the clock and the randoms on save/load.
	 */
	private static int peaceTimeEndMs;

	public static void init(IGameClock clock, long randomSeed) {
		init(clock, randomSeed, 0);
	}

	public static void init(IGameClock clock, long randomSeed, int peaceTimeEndMs) {
		clearState();
		MatchConstants.clock = clock;
		MatchConstants.gameRandom = new ExtendedRandom(randomSeed);
		MatchConstants.aiRandom = new ExtendedRandom(randomSeed);
		MatchConstants.peaceTimeEndMs = peaceTimeEndMs;

		BuildingWorkerMovable.resetProductionFile();
	}

	public static void clearState() {
		if (clock != null) {
			clock.stopExecution();
		}
		clock = null;
		gameRandom = null;
		aiRandom = null;
		peaceTimeEndMs = 0;
	}

	public static IGameClock clock() {
		return clock;
	}

	public static ExtendedRandom random() {
		return gameRandom;
	}

	public static ExtendedRandom aiRandom() {
		return aiRandom;
	}

	/**
	 * @return the game time in milliseconds at which the peacetime of the current match ends; 0 if the match has no peacetime.
	 */
	public static int getPeaceTimeEndMs() {
		return peaceTimeEndMs;
	}

	/**
	 * @return true while the peacetime of the current match is still running, meaning that no combat harm (damage / capturing) is
	 *         possible. Always false for matches created without a peacetime.
	 */
	public static boolean isPeaceTime() {
		return peaceTimeEndMs > 0 && clock.getTime() < peaceTimeEndMs;
	}

	public static void serialize(ObjectOutputStream oos) throws IOException {
		oos.writeInt(clock.getTime());
		oos.writeInt(peaceTimeEndMs);
		oos.writeObject(gameRandom);
		oos.writeObject(aiRandom);
	}

	/**
	 * @param containsPeaceTime
	 *            whether the stream was written by a version that serializes the peacetime end (see
	 *            {@link jsettlers.logic.map.loading.newmap.MapFileHeader#VERSION_PEACE_TIME_INTRODUCED}). Savegames from older versions
	 *            omit the field and load with no peacetime, keeping them playable.
	 */
	public static void deserialize(ObjectInputStream ois, boolean containsPeaceTime) throws IOException, ClassNotFoundException {
		clock.setTime(ois.readInt());
		peaceTimeEndMs = containsPeaceTime ? ois.readInt() : 0;
		gameRandom = (ExtendedRandom) ois.readObject();
		aiRandom = (ExtendedRandom) ois.readObject();
	}

}
