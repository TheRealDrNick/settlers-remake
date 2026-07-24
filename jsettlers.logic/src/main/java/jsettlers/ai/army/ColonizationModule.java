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

import jsettlers.common.action.EMoveToType;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.landscape.EResourceType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.movable.EShipType;
import jsettlers.common.movable.IGraphicsMovable;
import jsettlers.common.player.ECivilisation;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.input.tasks.ConvertGuiTask;
import jsettlers.input.tasks.EGuiAction;
import jsettlers.input.tasks.MovableGuiTask;
import jsettlers.input.tasks.OrderShipGuiTask;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.buildings.workers.DockyardBuilding;
import jsettlers.logic.map.grid.MainGrid;
import jsettlers.logic.map.grid.landscape.LandscapeGrid;
import jsettlers.logic.movable.interfaces.IFerryMovable;
import jsettlers.logic.movable.interfaces.ILogicMovable;

/**
 * Phase 1 of the cross-water colonization feature: the AI ships a small squad of pioneers across the sea to another landmass and claims a
 * beachhead of foreign ground there. No construction happens on the island yet - the pioneers only cross, land and convert territory.
 * <p>
 * The module mirrors {@link NavalInvasionModule}'s ferry pipeline (build a dockyard-provided ferry, load passengers onto its water tile,
 * sail to a landing tile, {@link EGuiAction#UNLOAD_FERRY unload}, then command the landed force), but it carries {@code PIONEER}s instead of
 * soldiers and its goal is to <em>settle</em> empty land rather than to attack. It relies on the Phase 0 sea-reachable-resource scan in
 * {@link jsettlers.ai.highlevel.AiStatistics} to decide whether an overseas objective exists at all.
 * <p>
 * Like {@link NavalInvasionModule} the module is <b>stateless</b>: every heavy tick it re-derives the whole situation from the game state
 * (which target is worthwhile, which ferry is ours, how many pioneers are aboard or already ashore) and issues the next order. It keeps no
 * serialized state, so it is robust across save/load and replays, and it uses no randomness (all ordering is deterministic), so it stays in
 * sync in multiplayer.
 * <h4>Inertness</h4>
 * On a map with no sea-reachable colonization target (e.g. a land-only map) the module issues <b>zero</b> {@code GuiTask}s every tick: it
 * returns early at the difficulty gate for easy AIs / the human, at the land-expansion gate while land is still claimable, at the dockyard
 * gate when no seaworthy infrastructure exists, and - most importantly - at the target gate, because
 * {@link jsettlers.ai.highlevel.AiStatistics#getSeaReachableResourceTargets(byte, EResourceType)} returns empty when the base has no
 * navigable coast or no off-landmass deposit. This keeps the difficulty ladder unaffected.
 *
 * @author jsettlers colonization AI
 */
public class ColonizationModule extends ArmyModule {

	// size of the pioneer squad shipped to establish a beachhead - a handful is enough to start claiming ground
	private static final int SQUAD_SIZE = 4;
	// passenger capacity of a ferry (mirrors NavalInvasionModule); the squad always fits on a single ferry
	private static final int FERRY_CAPACITY = 7;
	// distance below which a ferry is considered to have "arrived" at a target water position (mirrors NavalInvasionModule)
	private static final int FERRY_ARRIVAL_DISTANCE = 6;
	// landed pioneers within this distance of the landing tile (and on its landmass) are treated as the beachhead expedition
	private static final int BEACHHEAD_COMMAND_RADIUS = 15;
	// land expansion is considered (nearly) exhausted - and colonization worth considering - once at most this much home border is still
	// ingestible by pioneers. This is the same signal WhatToDoAi.commandPioneers() uses (it releases pioneers when the border is empty).
	private static final int BORDER_EXHAUSTED_THRESHOLD = 8;
	// jobless bearers kept at home before any are converted to colonization pioneers, so the home economy is never starved of carriers.
	// Scaled per game by the play style's naval caution below.
	private static final int BASE_MIN_JOBLESS_BEARER_RESERVE = 12;
	// a colonization target must rate strictly above this to be pursued (Phase 0 score = ore_gain - ferry_cost - build_cost - defense_risk)
	private static final double MIN_TARGET_VALUE = 0.0;

	// which difficulties colonize at all: only the two strong AIs. Indexed by EPlayerType.ordinal():
	// AI_VERY_EASY, AI_EASY, AI_HARD, AI_VERY_HARD, HUMAN - easy opponents and the human player never colonize across water.
	private static final boolean[] COLONIZATION_ENABLED_BY_PLAYER_TYPE = { false, false, true, true, false };

	private final LandscapeGrid landscapeGrid;
	private final byte playerId;
	private final boolean colonizationEnabled;
	private final int minJoblessBearerReserve;

	public ColonizationModule(ArmyFramework parent) {
		super(parent);
		MainGrid mainGrid = parent.aiStatistics.getMainGrid();
		this.landscapeGrid = mainGrid.getLandscapeGrid();
		this.playerId = parent.getPlayerId();
		this.colonizationEnabled = COLONIZATION_ENABLED_BY_PLAYER_TYPE[parent.getPlayer().getPlayerType().ordinal()];
		// a more naval-cautious play style (e.g. TURTLE) keeps a larger settler reserve at home before venturing overseas; a RAIDER commits
		// more eagerly. This only flavours the behaviour and never touches the difficulty-defining economy.
		this.minJoblessBearerReserve = Math.round(BASE_MIN_JOBLESS_BEARER_RESERVE * parent.getPlayStyle().navalCautionFactor);
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
		if (!colonizationEnabled) {
			return; // easy AIs and the human player never colonize across water
		}
		// gate 1 - decide whether we want to colonize at all this tick. Two ways in:
		//   (a) home land expansion is (nearly) exhausted - there is little left to claim at home, so look overseas; or
		//   (b) opportunistic: we have settlers to spare beyond the home reserve, so a strong nation expands overseas proactively rather
		//       than only when boxed in. On a large home island (a) may never happen within a game, so (b) is what actually gets the AI
		//       to colonize; the later gates (a ready dockyard + a positive-value sea target) still ensure it only does so when worthwhile.
		boolean landExpansionExhausted = parent.aiStatistics.getBorderIngestibleByPioneersOf(playerId).size() <= BORDER_EXHAUSTED_THRESHOLD;
		boolean settlersToSpare = joblessBearersBeyondReserve() >= SQUAD_SIZE;
		if (!landExpansionExhausted && !settlersToSpare) {
			return;
		}
		// gate 2 - seaworthy infrastructure must already exist (the economy builds the dockyard, WhatToDoAi.assignDocks() gives it a dock)
		DockyardBuilding dockyard = findReadyDockyard();
		if (dockyard == null || dockyard.getDock() == null) {
			return;
		}
		ShortPoint2D dockWater = dockyard.getDock().getWaterPosition();

		// gate 3 - there must be a worthwhile sea-reachable resource deposit. On maps without one this returns null every tick, so the whole
		// module is inert below this point (no GuiTasks are ever issued).
		ColonizationTarget target = selectColonizationTarget();
		if (target == null) {
			return;
		}

		// step 6 (runs first so a beachhead keeps advancing even while the next wave is still loading): pioneers already ashore walk onto the
		// ore, claiming foreign ground on the way (PioneerMovable.workOnPosition -> grid.changePlayerAt).
		commandBeachhead(target, soldiersWithOrders);

		// step 2 - ensure a ferry exists. The dockyard builds one ship at a time and ignores extra orders, so requesting each tick is safe.
		List<IFerryMovable> ferries = findFriendlyFerries();
		if (ferries.isEmpty()) {
			parent.taskScheduler.scheduleTask(new OrderShipGuiTask(playerId, dockyard, EShipType.FERRY));
			return;
		}
		IFerryMovable ferry = pickColonizationFerry(ferries, dockWater);
		if (ferry == null) {
			return; // every ferry is currently carrying soldiers for the naval invasion module - do not hijack it
		}

		int boarded = countPioneerPassengers(ferry);
		ShortPoint2D ferryPosition = ferry.getPosition();
		boolean atHome = ferryPosition.getOnGridDistTo(dockWater) <= FERRY_ARRIVAL_DISTANCE;

		if (atHome) {
			// steps 3 + 4 - assemble the squad at home: raise pioneers from idle bearers and send them onto the ferry's water tile to board
			List<ShortPoint2D> homePioneers = collectIdleHomePioneers(soldiersWithOrders);
			if (boarded < SQUAD_SIZE) {
				raisePioneers(SQUAD_SIZE - boarded - homePioneers.size());
				loadPioneers(ferry, homePioneers, SQUAD_SIZE - boarded, soldiersWithOrders);
			}
			// step 5 - set sail once the squad is complete, or once no more pioneers can be raised or boarded (sail with what we have)
			boolean noMoreReinforcements = homePioneers.isEmpty() && joblessBearersBeyondReserve() <= 0;
			if (boarded >= SQUAD_SIZE || (boarded > 0 && noMoreReinforcements)) {
				sendFerryTo(ferry, target.landingTile, soldiersWithOrders);
			}
		} else if (boarded > 0) {
			// step 5 - carrying settlers away from home: sail on and unload on arrival at the landing tile
			if (ferryPosition.getOnGridDistTo(target.landingTile) <= FERRY_ARRIVAL_DISTANCE) {
				parent.taskScheduler.scheduleTask(new MovableGuiTask(EGuiAction.UNLOAD_FERRY, playerId, listOf(ferry.getID())));
			} else {
				sendFerryTo(ferry, target.landingTile, soldiersWithOrders);
			}
		} else {
			sendFerryTo(ferry, dockWater, soldiersWithOrders); // empty and adrift - return home to pick up the squad
		}
	}

	/**
	 * Chooses the most worthwhile sea-reachable resource deposit to settle, iterating the resources the economy wants (mirrors
	 * {@link jsettlers.ai.highlevel.WhatToDoAi}'s needed-resources list) and scoring each with the Phase 0 heuristic. Deterministic: the
	 * resource list and the Phase 0 scan both iterate in a stable order and ties keep the earlier resource.
	 *
	 * @return the best target with a strictly positive score, or null if none qualifies.
	 */
	private ColonizationTarget selectColonizationTarget() {
		ColonizationTarget best = null;
		double bestValue = MIN_TARGET_VALUE;
		for (EResourceType resource : neededResources()) {
			List<ShortPoint2D> reachable = parent.aiStatistics.getSeaReachableResourceTargets(playerId, resource);
			if (reachable.isEmpty()) {
				continue;
			}
			double value = parent.aiStatistics.rateSeaReachableResourceTarget(playerId, resource, reachable);
			if (value > bestValue) {
				ShortPoint2D orePoint = reachable.get(0); // the Phase 0 rating uses the first deposit as the representative landing focus
				ShortPoint2D landing = parent.aiStatistics.getSeaReachableLandingNear(playerId, orePoint);
				if (landing != null) {
					bestValue = value;
					best = new ColonizationTarget(orePoint, landing);
				}
			}
		}
		return best;
	}

	private List<EResourceType> neededResources() {
		List<EResourceType> resources = new ArrayList<>();
		resources.add(EResourceType.COAL);
		resources.add(EResourceType.IRONORE);
		resources.add(EResourceType.GOLDORE);
		if (parent.getPlayer().getCivilisation() == ECivilisation.EGYPTIAN) {
			resources.add(EResourceType.GEMSTONE);
		}
		return resources;
	}

	/** Orders pioneers that have been ferried across and are ashore near the landing tile to walk onto the ore, claiming ground on the way. */
	private void commandBeachhead(ColonizationTarget target, Set<Integer> soldiersWithOrders) {
		List<ShortPoint2D> landed = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, EMovableType.PIONEER)) {
			if (parent.isReachableByLand(position)) {
				continue; // still on our home landmass - part of the home pool, not the overseas beachhead
			}
			if (position.getOnGridDistTo(target.landingTile) <= BEACHHEAD_COMMAND_RADIUS
					&& landscapeGrid.isReachable(target.landingTile.x, target.landingTile.y, position.x, position.y, false)) {
				landed.add(position);
			}
		}
		if (landed.isEmpty()) {
			return;
		}
		// DEFAULT lets the pioneers work on their way to the destination (EMoveToType.DEFAULT.isWorkOnDestination()), converting foreign ground
		parent.sendTroopsTo(landed, target.orePoint, soldiersWithOrders, EMoveToType.DEFAULT);
	}

	/** Converts up to {@code count} idle jobless bearers into pioneers, never dropping below the home carrier reserve. */
	private void raisePioneers(int count) {
		if (count <= 0) {
			return;
		}
		List<ShortPoint2D> joblessBearers = parent.aiStatistics.getPositionsOfJoblessBearersForPlayer(playerId);
		int convertible = Math.min(count, joblessBearers.size() - minJoblessBearerReserve);
		if (convertible <= 0) {
			return;
		}
		List<Integer> ids = new ArrayList<>(convertible);
		for (int i = 0; i < convertible; i++) {
			ShortPoint2D position = joblessBearers.get(i);
			ILogicMovable bearer = parent.movableGrid.getMovableAt(position.x, position.y);
			if (bearer != null) {
				ids.add(bearer.getID());
			}
		}
		if (!ids.isEmpty()) {
			parent.taskScheduler.scheduleTask(new ConvertGuiTask(playerId, ids, EMovableType.PIONEER));
		}
	}

	/**
	 * Sends up to {@code wanted} of the home pioneers onto the ferry's water tile to board it. Reuses NavalInvasionModule's technique:
	 * moving a player-controlled {@code IAttackableHumanMovable} onto a water tile that holds a friendly ferry makes it board (see
	 * GuiTaskExecutor.moveSelectedTo). {@code PioneerMovable} is player-controllable and shares {@code AttackableHumanMovable.moveToFerry},
	 * so pioneers board exactly like soldiers.
	 */
	private void loadPioneers(IFerryMovable ferry, List<ShortPoint2D> homePioneers, int wanted, Set<Integer> soldiersWithOrders) {
		int freeSpace = FERRY_CAPACITY - ferry.getPassengers().size();
		int toLoad = Math.min(Math.min(freeSpace, wanted), homePioneers.size());
		if (toLoad <= 0) {
			return;
		}
		List<ShortPoint2D> batch = new ArrayList<>(homePioneers.subList(0, toLoad));
		parent.sendTroopsTo(batch, ferry.getPosition(), soldiersWithOrders, EMoveToType.DEFAULT);
	}

	private int joblessBearersBeyondReserve() {
		return parent.aiStatistics.getPositionsOfJoblessBearersForPlayer(playerId).size() - minJoblessBearerReserve;
	}

	/** @return idle pioneers standing on our home landmass, i.e. candidates to be loaded onto the ferry. */
	private List<ShortPoint2D> collectIdleHomePioneers(Set<Integer> soldiersWithOrders) {
		List<ShortPoint2D> result = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, EMovableType.PIONEER)) {
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

	/**
	 * Picks the ferry to use for colonization: one already carrying our pioneers if any, otherwise an empty ferry waiting at home. Ferries
	 * that carry soldiers belong to the naval invasion module and are left alone.
	 */
	private IFerryMovable pickColonizationFerry(List<IFerryMovable> ferries, ShortPoint2D dockWater) {
		IFerryMovable emptyAtHome = null;
		for (IFerryMovable ferry : ferries) {
			if (countPioneerPassengers(ferry) > 0) {
				return ferry; // already carrying our settlers - keep using it
			}
			if (emptyAtHome == null && ferry.getPassengers().isEmpty()
					&& ferry.getPosition().getOnGridDistTo(dockWater) <= FERRY_ARRIVAL_DISTANCE) {
				emptyAtHome = ferry;
			}
		}
		return emptyAtHome;
	}

	private int countPioneerPassengers(IFerryMovable ferry) {
		int count = 0;
		for (IGraphicsMovable passenger : ferry.getPassengers()) {
			if (passenger.getMovableType() == EMovableType.PIONEER) {
				count++;
			}
		}
		return count;
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

	/** A chosen colonization target: the representative ore deposit to settle and the water tile the ferry unloads at. */
	private static final class ColonizationTarget {
		final ShortPoint2D orePoint;
		final ShortPoint2D landingTile;

		ColonizationTarget(ShortPoint2D orePoint, ShortPoint2D landingTile) {
			this.orePoint = orePoint;
			this.landingTile = landingTile;
		}
	}
}
