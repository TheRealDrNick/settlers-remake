package jsettlers.ai.army;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jsettlers.ai.highlevel.AiStatistics;
import jsettlers.common.CommonConstants;
import jsettlers.common.action.EMoveToType;
import jsettlers.common.ai.EPlayerType;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.ShortPoint2D;

/**
 * Army-preservation / retreat behaviour.
 *
 * <p>
 * A human player pulls a losing attack back and regroups instead of feeding soldiers one by one into a hopeless fight. This module gives the
 * AI the same instinct: every heavy tick it looks for a body of our soldiers that is engaged in a losing fight <em>away from home</em>
 * (locally outnumbered, out of range of our own towers) and orders it to fall back to a rally point rather than die in place. Fresh reserves
 * near home are left untouched, so {@link SimpleAttackStrategy} can keep pressing with them the same tick — only the losing body disengages,
 * which is exactly how a human micro-manages a bad engagement.
 * </p>
 *
 * <h2>Difficulty scaling</h2>
 * Two independent knobs both scale with {@link EPlayerType} (see {@link #RETREAT_ENEMY_ADVANTAGE_TRIGGER_BY_PLAYER_TYPE} and
 * {@link #RALLY_MODE_BY_PLAYER_TYPE}):
 * <ul>
 * <li><b>Trigger threshold</b> &ndash; how badly the fight must be going before we pull back. Stronger AIs react <em>early</em> (retreat at a
 * mild local disadvantage, preserving the army); weaker AIs react <em>late</em> (they keep bleeding troops into a lost fight). Encoded as the
 * enemy-to-own force ratio that must be reached before a retreat is ordered: a <em>lower</em> value = earlier, more army-preserving retreat.</li>
 * <li><b>Rally destination</b> &ndash; stronger AIs regroup <em>forward</em> at the nearest owned military building (stay aggressive and
 * defensible); weaker AIs retreat all the way to the main base (crude), and the human never retreats at all.</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * The module is stateless: every tick it re-derives the situation purely from {@link AiStatistics} read accessors, the {@code movableGrid} and
 * the soldier positions, and holds no state between ticks. It uses no randomness at all (all ordering and tie-breaks are deterministic on grid
 * coordinates), so it is trivially replay- and multiplayer-safe. Were a random tie-break ever needed it must come from
 * {@link jsettlers.logic.constants.MatchConstants#aiRandom()} only.
 *
 * @author Nico Wittmann
 */
public class RetreatModule extends ArmyModule {

	/**
	 * A soldier within this grid distance of one of our own military buildings is considered "at home": it fights with tower support and is
	 * not treated as an away engagement, so it is never told to retreat. Scaled off {@link CommonConstants#TOWER_RADIUS}, the natural unit of
	 * military influence used throughout the army AI.
	 */
	private static final int HOME_SAFE_RADIUS = CommonConstants.TOWER_RADIUS;

	/**
	 * Radius around one of our soldiers within which an enemy soldier counts as "locally engaging" it, and within which our own soldiers count
	 * as part of the same contested body. This defines the local balance-of-power sample that decides whether the fight is being lost.
	 */
	private static final int ENGAGEMENT_RADIUS = CommonConstants.TOWER_RADIUS;

	/**
	 * Trigger threshold, indexed by {@link EPlayerType#ordinal()} &ndash; {@code AI_VERY_EASY, AI_EASY, AI_HARD, AI_VERY_HARD, HUMAN}.
	 *
	 * <p>
	 * The value is the enemy-to-own force ratio in the contested area that must be met or exceeded before the engaged body is ordered to
	 * retreat. A <em>lower</em> threshold means the AI bails out at a milder disadvantage, i.e. reacts <em>earlier</em> and preserves more of
	 * its army:
	 * </p>
	 * <ul>
	 * <li>{@code AI_VERY_HARD = 1.05} &ndash; retreats the moment the enemy has even a slight local edge; barely loses troops to lost fights.</li>
	 * <li>{@code AI_HARD = 1.3} &ndash; tolerates a small disadvantage, then pulls back.</li>
	 * <li>{@code AI_EASY = 2.0} &ndash; only bails when clearly outnumbered 2:1, so it bleeds troops first.</li>
	 * <li>{@code AI_VERY_EASY = 3.0} &ndash; only bails from a total rout (3:1); feeds most of the doomed body in before reacting.</li>
	 * <li>{@code HUMAN = +Inf} &ndash; never auto-retreats; the human is in control.</li>
	 * </ul>
	 * This ordering deliberately reinforces the difficulty ladder: the stronger the AI, the more of its standing army survives a bad
	 * engagement, so it has more troops for the next fight.
	 */
	private static final float[] RETREAT_ENEMY_ADVANTAGE_TRIGGER_BY_PLAYER_TYPE = { 3.0f, 2.0f, 1.3f, 1.05f, Float.POSITIVE_INFINITY };

	/** Where a retreating body regroups. */
	private enum RallyMode {
		/** Do not retreat at all (human, or any type whose trigger is disabled). */
		NONE,
		/** Crude retreat all the way to the main base (most central owned military building). Used by the weaker AIs. */
		BASE,
		/** Aggressive fall-back to the nearest owned military building behind the front. Used by the stronger AIs. */
		FORWARD
	}

	/**
	 * Rally destination, indexed by {@link EPlayerType#ordinal()} &ndash; {@code AI_VERY_EASY, AI_EASY, AI_HARD, AI_VERY_HARD, HUMAN}. Stronger
	 * AIs regroup forward and stay aggressive; weaker AIs fall all the way back to base; the human never retreats.
	 */
	private static final RallyMode[] RALLY_MODE_BY_PLAYER_TYPE = { RallyMode.BASE, RallyMode.BASE, RallyMode.FORWARD, RallyMode.FORWARD,
			RallyMode.NONE };

	private final float retreatTrigger;
	private final RallyMode rallyMode;

	public RetreatModule(ArmyFramework parent) {
		super(parent);

		int typeIndex = parent.getPlayer().getPlayerType().ordinal();
		this.retreatTrigger = RETREAT_ENEMY_ADVANTAGE_TRIGGER_BY_PLAYER_TYPE[typeIndex];
		this.rallyMode = RALLY_MODE_BY_PLAYER_TYPE[typeIndex];
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
		// disabled difficulties (human) never auto-retreat
		if (rallyMode == RallyMode.NONE || Float.isInfinite(retreatTrigger)) {
			return;
		}

		List<ShortPoint2D> ownMilitaryBuildings = parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS,
				parent.getPlayerId());
		if (ownMilitaryBuildings.isEmpty()) {
			return; // nowhere to rally to (and effectively already defeated)
		}

		List<ShortPoint2D> enemySoldiers = collectEnemySoldierPositions();
		if (enemySoldiers.isEmpty()) {
			return; // no fight to lose
		}

		// Only preserve the army when we are actually losing the war. While we hold overall army superiority we press the advantage and
		// never pull an assault back - otherwise a stronger AI attacking a defended base (where its spearhead is transiently, locally
		// outnumbered by the garrison) would repeatedly retreat and never finish the conquest. Retreat is a comeback/survival instinct for
		// the side that is behind, not a brake on the side that is winning.
		// Only preserve the army once we are CLEARLY losing the war (fewer than ~75% of the enemy's total soldiers). A near-parity or
		// winning AI keeps pressing, so this never brakes a decisive assault. (A looser "any disadvantage" guard still made a strong AI
		// too timid to conquer the benchmark opponent in time on some civilisations.)
		int ourTotalSoldiers = parent.aiStatistics.getCountOfMovablesOfPlayer(parent.getPlayer(), EMovableType.SOLDIERS);
		if (ourTotalSoldiers * 4 >= enemySoldiers.size() * 3) {
			return;
		}

		// A soldier is part of a losing away-body if it is out of tower range of home AND has at least one enemy soldier close by.
		List<ShortPoint2D> engagedSoldiers = new ArrayList<>();
		for (ShortPoint2D soldier : parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(parent.getPlayerId(), EMovableType.SOLDIERS)) {
			if (isAtHome(soldier, ownMilitaryBuildings)) {
				continue;
			}
			if (hasEnemyWithin(soldier, enemySoldiers, ENGAGEMENT_RADIUS)) {
				engagedSoldiers.add(soldier);
			}
		}
		if (engagedSoldiers.isEmpty()) {
			return; // no away engagement in progress
		}

		// Local balance of power: our engaged body vs. every enemy soldier actually in contact with it. Counting distinct enemy positions
		// (via a set) avoids double-counting an enemy that is near several of our soldiers.
		int ourForce = engagedSoldiers.size();
		int enemyForce = countEnemiesEngaging(engagedSoldiers, enemySoldiers);

		// losing when the enemy's local force meets/exceeds our force times the difficulty-scaled trigger ratio
		if (enemyForce < ourForce * retreatTrigger) {
			return; // fight is not (yet) bad enough for this difficulty to pull back
		}

		ShortPoint2D rallyPoint = chooseRallyPoint(engagedSoldiers, ownMilitaryBuildings);
		if (rallyPoint == null) {
			return;
		}

		// FORCED so the body actually breaks contact and runs, rather than lingering in the losing melee. Adding the ids to
		// soldiersWithOrders (done inside sendTroopsTo) means the later attack/regroup modules leave the retreating body alone this tick.
		parent.sendTroopsTo(engagedSoldiers, rallyPoint, soldiersWithOrders, EMoveToType.FORCED);
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {
		// retreat decisions are re-evaluated on the heavy tick; nothing to do on the fast tick
	}

	private List<ShortPoint2D> collectEnemySoldierPositions() {
		List<ShortPoint2D> enemySoldiers = new ArrayList<>();
		for (IPlayer enemy : parent.aiStatistics.getAliveEnemiesOf(parent.getPlayer())) {
			enemySoldiers.addAll(parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(enemy.getPlayerId(), EMovableType.SOLDIERS));
		}
		return enemySoldiers;
	}

	private boolean isAtHome(ShortPoint2D soldier, List<ShortPoint2D> ownMilitaryBuildings) {
		for (ShortPoint2D building : ownMilitaryBuildings) {
			if (soldier.getOnGridDistTo(building) <= HOME_SAFE_RADIUS) {
				return true;
			}
		}
		return false;
	}

	private boolean hasEnemyWithin(ShortPoint2D soldier, List<ShortPoint2D> enemySoldiers, int radius) {
		for (ShortPoint2D enemy : enemySoldiers) {
			if (soldier.getOnGridDistTo(enemy) <= radius) {
				return true;
			}
		}
		return false;
	}

	private int countEnemiesEngaging(List<ShortPoint2D> engagedSoldiers, List<ShortPoint2D> enemySoldiers) {
		Set<ShortPoint2D> engagingEnemies = new HashSet<>();
		for (ShortPoint2D enemy : enemySoldiers) {
			if (hasEnemyWithin(enemy, engagedSoldiers, ENGAGEMENT_RADIUS)) {
				engagingEnemies.add(enemy);
			}
		}
		return engagingEnemies.size();
	}

	/**
	 * @return the tile the losing body should fall back to, or {@code null} if none can be determined. FORWARD picks the owned military
	 *         building nearest to the body itself (the defensible position just behind the front); BASE picks the building nearest the centre
	 *         of all our military buildings (the crude, deep retreat to the main base).
	 */
	private ShortPoint2D chooseRallyPoint(List<ShortPoint2D> engagedSoldiers, List<ShortPoint2D> ownMilitaryBuildings) {
		ShortPoint2D reference;
		if (rallyMode == RallyMode.FORWARD) {
			reference = AiStatistics.calculateAveragePointFromList(engagedSoldiers);
		} else { // BASE
			reference = AiStatistics.calculateAveragePointFromList(ownMilitaryBuildings);
		}
		return nearestTo(reference, ownMilitaryBuildings);
	}

	private ShortPoint2D nearestTo(ShortPoint2D reference, List<ShortPoint2D> candidates) {
		ShortPoint2D best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ShortPoint2D candidate : candidates) {
			int distance = candidate.getOnGridDistTo(reference);
			// deterministic tie-break on grid coordinates so the choice never depends on list order or a random source
			if (distance < bestDistance || (distance == bestDistance && isLowerCoordinate(candidate, best))) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}

	private boolean isLowerCoordinate(ShortPoint2D candidate, ShortPoint2D current) {
		if (current == null) {
			return true;
		}
		return candidate.x < current.x || (candidate.x == current.x && candidate.y < current.y);
	}
}
