package jsettlers.logic.player;

/**
 * The peacetime options a match can be created with.
 * <p>
 * While the peacetime of a match is running, no player (human or AI) can do any combat harm: movables receive no combat damage and
 * military buildings can neither have their door attacked nor be captured. Economy, movement and building are unaffected. When the
 * peacetime is over, war works exactly as without one.
 * <p>
 * The selected value is part of {@link InitialGameState} and thereby part of the deterministic match state (it is identical for all
 * participants of a multiplayer match and for replays, and it survives save/load via
 * {@link jsettlers.logic.constants.MatchConstants}).
 *
 * @author Nico Wittmann
 */
public enum EPeaceTime {
	WITHOUT(0),
	MINUTES_5(5),
	MINUTES_10(10),
	MINUTES_15(15),
	MINUTES_20(20),
	MINUTES_25(25),
	MINUTES_30(30);

	/**
	 * The duration of the peacetime in game time minutes. A value of 0 means the match is played without a peacetime.
	 */
	public final int minutes;

	EPeaceTime(int minutes) {
		this.minutes = minutes;
	}

	/**
	 * @return the game time in milliseconds at which this peacetime ends. 0 means there is no peacetime at all.
	 */
	public int getEndTimeMs() {
		return minutes * 60 * 1000;
	}
}
