/*******************************************************************************
 * Copyright (c) 2015 - 2019
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
package jsettlers.ai.highlevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import java.util.Objects;
import java.util.stream.Collectors;

import jsettlers.ai.highlevel.AiPositions.AiPositionFilter;
import jsettlers.algorithms.construction.AbstractConstructionMarkableMap;
import jsettlers.common.CommonConstants;
import jsettlers.common.buildings.BuildingVariant;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.buildings.IMaterialProductionSettings;
import jsettlers.common.landscape.ELandscapeType;
import jsettlers.common.landscape.EResourceType;
import jsettlers.common.map.shapes.HexGridArea;
import jsettlers.common.mapobject.EMapObjectType;
import jsettlers.common.material.EMaterialType;
import jsettlers.common.movable.EDirection;
import jsettlers.common.movable.EMovableAction;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.player.ECivilisation;
import jsettlers.common.player.EWinState;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.RelativePoint;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.buildings.WorkAreaBuilding;
import jsettlers.logic.map.grid.MainGrid;
import jsettlers.logic.map.grid.flags.FlagsGrid;
import jsettlers.logic.map.grid.landscape.LandscapeGrid;
import jsettlers.logic.map.grid.movable.MovableGrid;
import jsettlers.logic.map.grid.objects.AbstractHexMapObject;
import jsettlers.logic.map.grid.objects.ObjectsGrid;
import jsettlers.logic.map.grid.partition.PartitionsGrid;
import jsettlers.logic.movable.interfaces.ILogicMovable;
import jsettlers.logic.player.Player;

import static jsettlers.common.buildings.EBuildingType.BIG_TOWER;
import static jsettlers.common.buildings.EBuildingType.CASTLE;
import static jsettlers.common.buildings.EBuildingType.FARM;
import static jsettlers.common.buildings.EBuildingType.LUMBERJACK;
import static jsettlers.common.buildings.EBuildingType.RICE_FARM;
import static jsettlers.common.buildings.EBuildingType.TOWER;
import static jsettlers.common.buildings.EBuildingType.WINEGROWER;
import static jsettlers.common.mapobject.EMapObjectType.CUT_OFF_STONE;
import static jsettlers.common.mapobject.EMapObjectType.STONE;
import static jsettlers.common.mapobject.EMapObjectType.TREE_ADULT;
import static jsettlers.common.mapobject.EMapObjectType.TREE_GROWING;
import static jsettlers.common.movable.EMovableType.BEARER;
import static jsettlers.common.movable.EMovableType.SWORDSMAN_L1;
import static jsettlers.common.movable.EMovableType.SWORDSMAN_L2;
import static jsettlers.common.movable.EMovableType.SWORDSMAN_L3;

/**
 * This class calculates statistics based on the grids which are used by highlevel and lowlevel KI. The statistics are calculated once and read multiple times within one AiExecutor step triggerd by
 * the game clock.
 *
 * @author codingberlin
 */
public class AiStatistics {

	private static final EBuildingType[] REFERENCE_POINT_FINDER_BUILDING_ORDER = { LUMBERJACK, TOWER, BIG_TOWER, CASTLE };
	private static final RelativePoint[] FISH_PARTITION_OFFSET = new RelativePoint[] {
			new RelativePoint(3, 0),
			new RelativePoint(-3, 0),
			new RelativePoint(0, 3),
			new RelativePoint(0, -3)
	};

	private static final int NEAR_STONE_DISTANCE = 5;

	private final MainGrid mainGrid;
	private final Queue<Building> buildings;
	private final PlayerStatistic[] playerStatistics;
	private final Map<EMapObjectType, AiPositions> sortedCuttableObjectsInDefaultPartition;
	private final AiPositions[] sortedResourceTypes;
	private final AiPositions sortedRiversInDefaultPartition;
	private final LandscapeGrid landscapeGrid;
	private final ObjectsGrid objectsGrid;
	private final PartitionsGrid partitionsGrid;
	private final MovableGrid movableGrid;
	private final FlagsGrid flagsGrid;
	private final AbstractConstructionMarkableMap constructionMarksGrid;
	private final AiMapInformation aiMapInformation;
	private final AiPartitionResources defaultPartitionResources;
	private final List<Player> players;

	private final ExecutorService statisticsUpdaterPool;
	private final Set<Callable<Void>> parallelStatisticsUpdater;

	public AiStatistics(MainGrid mainGrid, ExecutorService threadPool) {
		this.mainGrid = mainGrid;
		buildings = Building.getAllBuildings();
		landscapeGrid = mainGrid.getLandscapeGrid();
		objectsGrid = mainGrid.getObjectsGrid();
		partitionsGrid = mainGrid.getPartitionsGrid();
		movableGrid = mainGrid.getMovableGrid();
		flagsGrid = mainGrid.getFlagsGrid();
		constructionMarksGrid = mainGrid.getConstructionMarksGrid();
		playerStatistics = new PlayerStatistic[mainGrid.getGuiInputGrid().getNumberOfPlayers()];
		defaultPartitionResources = new AiPartitionResources();
		aiMapInformation = new AiMapInformation(partitionsGrid, landscapeGrid, defaultPartitionResources);
		for (byte i = 0; i < mainGrid.getGuiInputGrid().getNumberOfPlayers(); i++) {
			this.playerStatistics[i] = new PlayerStatistic();
		}
		sortedRiversInDefaultPartition = new AiPositions();
		sortedCuttableObjectsInDefaultPartition = new HashMap<>();
		sortedResourceTypes = new AiPositions[EResourceType.VALUES.length];
		for (int i = 0; i < sortedResourceTypes.length; i++) {
			sortedResourceTypes[i] = new AiPositions();
		}
		players = Arrays.stream(partitionsGrid.getPlayers()).filter(Objects::nonNull).collect(Collectors.toList());

		statisticsUpdaterPool = threadPool;
		parallelStatisticsUpdater = Set.of(this::mainMapStatUpdater, this::freeLandMapStatUpdater, this::playerLandMapStatUpdater, this::movableMapStatUpdater, this::surfaceMapStatUpdater, this::pioneerMapStatUpdater);
	}

	public byte getFlatternEffortAtPositionForBuilding(final ShortPoint2D position, final BuildingVariant buildingType) {
		byte flattenEffort = constructionMarksGrid.calculateConstructionMarkValue(position.x, position.y, buildingType.getProtectedTiles());
		if (flattenEffort == -1) {
			return Byte.MAX_VALUE;
		}
		return flattenEffort;
	}

	public void updateStatistics() {
		for (PlayerStatistic playerStatistic : playerStatistics) {
			playerStatistic.clearAll();
		}

		defaultPartitionResources.clear();
		sortedRiversInDefaultPartition.clear();
		sortedCuttableObjectsInDefaultPartition.clear();
		for (AiPositions xCoordinatesMap : sortedResourceTypes) {
			xCoordinatesMap.clear();
		}

		updateBuildingStatistics();
		updateMapStatistics();
	}

	private void updateBuildingStatistics() {
		for (Building building : buildings) {
			PlayerStatistic playerStatistic = playerStatistics[building.getPlayer().getPlayerId()];
			EBuildingType type = building.getBuildingVariant().getType();
			updateNumberOfNotFinishedBuildings(playerStatistic, building);
			updateBuildingsNumbers(playerStatistic, building, type);
			updateBuildingPositions(playerStatistic, type, building);
		}
	}

	private void updateBuildingPositions(PlayerStatistic playerStatistic, EBuildingType type, Building building) {
		playerStatistic.buildingPositions.computeIfAbsent(type, t -> new ArrayList<>()).add(building.getPosition());

		switch (type) {
			case WINEGROWER:
			case FARM:
			case RICE_FARM:
				playerStatistic.buildingWorkAreas.computeIfAbsent(type, t -> new ArrayList<>()).add(((WorkAreaBuilding)building).getWorkAreaCenter());
				break;
			case HOSPITAL:
				if (building.getStateProgress() == 1f) {
					playerStatistic.activeHospitals.add(building.getPosition());
				}
				break;
		}
	}

	private void updateBuildingsNumbers(PlayerStatistic playerStatistic, Building building, EBuildingType type) {
		playerStatistic.totalBuildingsNumbers[type.ordinal]++;
		if (building.getStateProgress() == 1f) {
			playerStatistic.buildingsNumbers[type.ordinal]++;
		}
	}

	private void updateNumberOfNotFinishedBuildings(PlayerStatistic playerStatistic, Building building) {
		playerStatistic.numberOfTotalBuildings++;
		if (building.getStateProgress() < 1f) {
			playerStatistic.numberOfNotFinishedBuildings++;
			if (building.getBuildingVariant().getType().isMilitaryBuilding()) {
				playerStatistic.numberOfNotOccupiedMilitaryBuildings++;
			}
		} else if (building.getBuildingVariant().getType().isMilitaryBuilding()) {
			if (!building.isOccupied()) {
				playerStatistic.numberOfNotOccupiedMilitaryBuildings++;
			}
		}
	}

	private Void mainMapStatUpdater() {
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();

		for (short x = 0; x < width; x++) {
			for (short y = 0; y < height; y++) {


				if (landscapeGrid.getResourceAmountAt(x, y) > 0) {
					AiPartitionResources partition = getPartitionFor(x, y);

					EResourceType resourceType = landscapeGrid.getResourceTypeAt(x, y);
					sortedResourceTypes[resourceType.ordinal].addNoCollission(x, y);
					if (resourceType != EResourceType.FISH) {
						partition.resourceCount[resourceType.ordinal]++;
					} else if (landscapeGrid.getLandscapeTypeAt(x, y) == ELandscapeType.WATER1) {
						AiPartitionResources fishPartition = partition;

						for(RelativePoint pt : FISH_PARTITION_OFFSET) {
							if(!defaultPartitionResources.equals(fishPartition)) break;

							fishPartition = getPartitionFor(pt.calculateX(x), pt.calculateY(y));
						}

						fishPartition.resourceCount[resourceType.ordinal]++;
					}
				}
			}
		}
		return null;
	}

	private Void surfaceMapStatUpdater() {
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();

		for(short x = 0; x < width; x++) {
			for(short y = 0; y < height; y++) {
				ELandscapeType type = landscapeGrid.getLandscapeTypeAt(x, y);

				if(type.isGrass()) {
					getPartitionFor(x, y).grassCount++;
				} else if(!type.isBlocking && type.isMoor()) {
					getPartitionFor(x, y).usableSwampCount++;
				}
			}
		}
		return null;
	}

	private Void movableMapStatUpdater() {
		for(ILogicMovable movable : movableGrid.getMovableArray()) {
			if (movable == null) continue;
			ShortPoint2D movablePosition = movable.getPosition();
			Player player = partitionsGrid.getPlayerAt(movablePosition.x, movablePosition.y);

			Player movablePlayer = movable.getPlayer();
			byte movablePlayerId = movablePlayer.playerId;
			PlayerStatistic movablePlayerStatistic = playerStatistics[movablePlayerId];
			EMovableType movableType = movable.getMovableType();
			movablePlayerStatistic.movablePositions.computeIfAbsent(movableType, key -> new ArrayList<>()).add(movablePosition);

			if (movableType == BEARER && movable.getAction() == EMovableAction.NO_ACTION) {
				playerStatistics[movablePlayerId].joblessBearerPositions.add(movable.getPosition());
			}
			if (player != null && player.playerId != movablePlayerId && movableType.isSoldier() && getEnemiesOf(player).contains(movablePlayer)) {
				playerStatistics[player.playerId].enemyTroopsInTown.addNoCollission(movablePosition.x, movablePosition.y);
			}
		}
		return null;
	}

	private Void freeLandMapStatUpdater() {
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();

		for (short x = 0; x < width; x++) {
			for (short y = 0; y < height; y++) {
				Player player = partitionsGrid.getPlayerAt(x, y);

				if (player == null) {
					updateFreeLand(x, y);
				}
			}
		}
		return null;
	}

	private Void playerLandMapStatUpdater() {
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();

		for (short x = 0; x < width; x++) {
			for (short y = 0; y < height; y++) {
				Player player = partitionsGrid.getPlayerAt(x, y);
				if(player == null) continue;

				if (isBuildablePartitionForPlayer(x, y, playerStatistics[player.playerId])) {
					updatePlayerLand(x, y, player);
				}
			}
		}
		return null;
	}

	/**
	 * Decides whether a tile the player owns counts as buildable land for that player. Historically the AI modelled every player as owning a
	 * single partition ({@link PlayerStatistic#partitionIdToBuildOn}, the partition of its first tower/castle) and only that partition was
	 * buildable. Cross-water colonization (Phase 1) lets the player claim a beachhead on a <em>different</em> landmass; Phase 2 must recognise
	 * that beachhead as buildable so an outpost can be raised there.
	 * <p>
	 * A tile is buildable if it is on the home partition (the original behaviour) or if it is a claimed beachhead: a walkable tile the player
	 * owns that lies on a landmass which is <b>not reachable by land</b> from the player's base (i.e. it can only be reached across water).
	 * <p>
	 * <b>Inertness.</b> When the player owns exactly one buildable partition - always the case on land-only maps such as the difficulty-test
	 * map {@code SpezialSumpf}, where no beachhead is ever claimed - every owned tile is on {@code partitionIdToBuildOn} and the first branch
	 * returns immediately, so the beachhead branch is never evaluated and behaviour is byte-for-byte identical to the previous single-partition
	 * logic. The beachhead branch only ever fires for a tile on a genuinely different landmass, which cannot exist on a single-landmass map.
	 */
	private boolean isBuildablePartitionForPlayer(int x, int y, PlayerStatistic playerStatistic) {
		if (partitionsGrid.getPartitionIdAt(x, y) == playerStatistic.partitionIdToBuildOn) {
			return true; // home partition - original, unchanged behaviour
		}
		ShortPoint2D reference = playerStatistic.referencePosition;
		if (reference == null) {
			return false; // no base yet, so "across water" is undefined
		}
		// a claimed beachhead: a walkable owned tile on a landmass the base cannot reach by land. Restricting to walkable tiles keeps the
		// landmass comparison well-defined (isReachable treats blocked tiles as unreachable) and matches the old behaviour for blocked tiles.
		return !landscapeGrid.isBlockedFor(x, y, false)
				&& !landscapeGrid.isReachable(x, y, reference.x, reference.y, false);
	}

	private Void pioneerMapStatUpdater() {
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();

		for (short x = 0; x < width; x++) {
			for (short y = 0; y < height; y++) {
				Player player = partitionsGrid.getPlayerAt(x, y);
				if (player == null) continue;
				if (hasNeighborIngestibleByPioneersOf(x, y, player)) {
					if (partitionsGrid.getPartitionIdAt(x, y) == playerStatistics[player.playerId].partitionIdToBuildOn) {
						playerStatistics[player.playerId].borderIngestibleByPioneers.add(x, y);
					} else {
						playerStatistics[player.playerId].otherPartitionBorder.add(x, y);
					}

				}
			}
		}
		return null;
	}

	private void updateMapStatistics() {
		updatePartitionIdsToBuildOn();

		try {
			statisticsUpdaterPool.invokeAll(parallelStatisticsUpdater);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private AiPartitionResources getPartitionFor(int x, int y) {
		if (!mainGrid.isInBounds(x, y)) {
			return defaultPartitionResources;
		}

		byte playerId = mainGrid.getPartitionsGrid().getPlayerIdAt(x, y);
		if (playerId == -1) {
			return defaultPartitionResources;
		}

		return playerStatistics[playerId].partitionResources;
	}

	private boolean hasNeighborIngestibleByPioneersOf(int x, int y, Player player) {
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();

		for (EDirection direction : EDirection.VALUES) {
			int dx = direction.gridDeltaX + x;
			int dy = direction.gridDeltaY + y;

			if(dx >= 0 && dy >= 0 && dx < width && dy < height && isIngestibleByPioneersOf(dx, dy, player)) {
				return true;
			}
		}
		return false;
	}

	private boolean isIngestibleByPioneersOf(int x, int y, Player player) {
		Player otherPlayer = partitionsGrid.getPlayerAt(x, y);
		return !player.hasSameTeam(otherPlayer)
				&& !flagsGrid.isBlocked(x, y)
				&& !partitionsGrid.isEnforcedByTower(x, y);
	}

	private void updatePlayerLand(short x, short y, Player player) {
		byte playerId = player.playerId;
		PlayerStatistic playerStatistic = playerStatistics[playerId];
		if (mainGrid.getFlagsGrid().isProtected(x, y)) {
			AbstractHexMapObject o = objectsGrid.getObjectsAt(x, y);
			if (o != null) {
				if (o.hasCuttableObject(STONE) && isCuttableByPlayer(x, y, player.playerId)) {
					playerStatistic.stones.addNoCollission(x, y);
				} else if (o.hasMapObjectTypes(TREE_GROWING, TREE_ADULT) && isCuttableByPlayer(x, y, player.playerId)) {
					playerStatistic.trees.addNoCollission(x, y);
				}

				if(o.hasMapObjectTypes(STONE, CUT_OFF_STONE)) {
					playerStatistic.partitionResources.stoneCount++;
				}
			}
		} else {
			playerStatistic.landToBuildOn.addNoCollission(x, y);
		}
		ELandscapeType landscape = landscapeGrid.getLandscapeTypeAt(x, y);
		if (landscape.isRiver()) {
			playerStatistic.rivers.addNoCollission(x, y);
		}
		if (objectsGrid.hasMapObjectType(x, y, EMapObjectType.WINE_GROWING, EMapObjectType.WINE_HARVESTABLE)) {
			playerStatistic.wineCount++;
		}
	}

	private boolean isCuttableByPlayer(short x, short y, byte playerId) {
		byte[] playerIds = new byte[4];
		playerIds[0] = partitionsGrid.getPlayerIdAt(x - 2, y - 2);
		playerIds[1] = partitionsGrid.getPlayerIdAt(x - 2, y + 2);
		playerIds[2] = partitionsGrid.getPlayerIdAt(x + 2, y - 2);
		playerIds[3] = partitionsGrid.getPlayerIdAt(x + 2, y + 2);
		for (byte positionPlayerId : playerIds) {
			if (positionPlayerId != playerId) {
				return false;
			}
		}
		return true;
	}

	private void updateFreeLand(short x, short y) {
		if (objectsGrid.hasCuttableObject(x, y, TREE_ADULT)) {
			AiPositions trees = sortedCuttableObjectsInDefaultPartition.get(TREE_ADULT);
			if (trees == null) {
				trees = new AiPositions();
				sortedCuttableObjectsInDefaultPartition.put(TREE_ADULT, trees);
			}
			trees.addNoCollission(x, y);
		}
		if (objectsGrid.hasCuttableObject(x, y, STONE)) {
			AiPositions stones = sortedCuttableObjectsInDefaultPartition.get(STONE);
			if (stones == null) {
				stones = new AiPositions();
				sortedCuttableObjectsInDefaultPartition.put(STONE, stones);
			}
			stones.addNoCollission(x, y);
			updateNearStones(x, y);
		}
		if (objectsGrid.hasMapObjectType(x, y, STONE, CUT_OFF_STONE)) {
			defaultPartitionResources.stoneCount++;
		}
		ELandscapeType landscape = landscapeGrid.getLandscapeTypeAt(x, y);
		if (landscape.isRiver()) {
			sortedRiversInDefaultPartition.addNoCollission(x, y);
		}
	}

	private void updateNearStones(short x, short y) {
		for (EDirection dir : EDirection.VALUES) {
			int currX = dir.getNextTileX(x, NEAR_STONE_DISTANCE);
			int currY = dir.getNextTileY(y, NEAR_STONE_DISTANCE);
			if (mainGrid.isInBounds(currX, currY)) {
				byte playerId = partitionsGrid.getPlayerIdAt(currX, currY);
				if (playerId != -1 && hasPlayersBlockedPartition(playerId, x, y)) {
					playerStatistics[playerId].stonesNearBy.addNoCollission(x, y);
				}
			}
		}
	}

	private void updatePartitionIdsToBuildOn() {
		for (byte playerId = 0; playerId < playerStatistics.length; playerId++) {
			ShortPoint2D referencePosition = null;
			for (EBuildingType referenceFinderBuildingType : REFERENCE_POINT_FINDER_BUILDING_ORDER) {
				if (getTotalNumberOfBuildingTypeForPlayer(referenceFinderBuildingType, playerId) > 0) {
					referencePosition = getBuildingPositionsOfTypeForPlayer(referenceFinderBuildingType, playerId).get(0);
					break;
				}
			}

			if (referencePosition != null) {
				PlayerStatistic playerStatistic = playerStatistics[playerId];
				playerStatistic.referencePosition = referencePosition;
				playerStatistic.partitionIdToBuildOn = partitionsGrid.getPartitionIdAt(referencePosition.x, referencePosition.y);
				playerStatistic.materialProduction = partitionsGrid.getMaterialProductionAt(referencePosition.x, referencePosition.y);
				playerStatistic.materials = partitionsGrid.getPartitionDataForManagerAt(referencePosition.x, referencePosition.y);
			}
		}
	}

	public Building getBuildingAt(ShortPoint2D point) {
		return (Building) objectsGrid.getMapObjectAt(point.x, point.y, EMapObjectType.BUILDING);
	}

	ShortPoint2D getNearestResourcePointForPlayer(ShortPoint2D point, EResourceType resourceType, byte playerId, int searchDistance, AiPositionFilter filter) {
		return getNearestPointInDefaultPartitionOutOfSortedMap(point, sortedResourceTypes[resourceType.ordinal], playerId, searchDistance, filter);
	}

	public ShortPoint2D getNearestFishPointForPlayer(ShortPoint2D point, final byte playerId, int currentNearestPointDistance) {
		return sortedResourceTypes[EResourceType.FISH.ordinal].getNearestPoint(point, currentNearestPointDistance, new AiPositionFilter() {
			@Override
			public boolean contains(int x, int y) {
				return isPlayerThere(x + 3, y) || isPlayerThere(x - 3, y) || isPlayerThere(x, y + 3) || isPlayerThere(x, y - 3);
			}

			private boolean isPlayerThere(int x, int y) {
				return mainGrid.isInBounds(x, y) && partitionsGrid.getPartitionAt(x, y).getPlayerId() == playerId;
			}
		});
	}

	public ShortPoint2D getNearestResourcePointInDefaultPartitionFor(ShortPoint2D point, EResourceType resourceType, int currentNearestPointDistance, AiPositionFilter filter) {
		return getNearestResourcePointForPlayer(point, resourceType, (byte) -1, currentNearestPointDistance, filter);
	}

	public ShortPoint2D getNearestCuttableObjectPointInDefaultPartitionFor(ShortPoint2D point, EMapObjectType cuttableObject, int searchDistance, AiPositionFilter filter) {
		return getNearestCuttableObjectPointForPlayer(point, cuttableObject, searchDistance, (byte) -1, filter);
	}

	private ShortPoint2D getNearestCuttableObjectPointForPlayer(ShortPoint2D point, EMapObjectType cuttableObject, int searchDistance, byte playerId, AiPositionFilter filter) {
		AiPositions sortedResourcePoints = sortedCuttableObjectsInDefaultPartition.get(cuttableObject);
		if (sortedResourcePoints == null) {
			return null;
		}

		return getNearestPointInDefaultPartitionOutOfSortedMap(point, sortedResourcePoints, playerId, searchDistance, filter);
	}

	private ShortPoint2D getNearestPointInDefaultPartitionOutOfSortedMap(ShortPoint2D point, AiPositions sortedPoints, final byte playerId, int searchDistance, final AiPositionFilter filter) {
		return sortedPoints.getNearestPoint(point, searchDistance, new AiPositions.CombinedAiPositionFilter((x, y) -> partitionsGrid.getPartitionAt(x, y).getPlayerId() == playerId, filter));
	}

	public boolean hasPlayersBlockedPartition(byte playerId, int x, int y) {
		ShortPoint2D reference = playerStatistics[playerId].referencePosition;
		return landscapeGrid.isReachable(x, y, reference.x, reference.y, false);
	}

	public List<ShortPoint2D> getPositionsOfMovablesWithTypesForPlayer(byte playerId, Set<EMovableType> movableTypes) {
		List<ShortPoint2D> movablePositions = new ArrayList<>();
		for(EMovableType movableType : movableTypes) {
			movablePositions.addAll(getPositionsOfMovablesWithTypeForPlayer(playerId, movableType));
		}
		return movablePositions;
	}

	public List<ShortPoint2D> getPositionsOfMovablesWithTypeForPlayer(byte playerId, EMovableType movableType) {
		if (!playerStatistics[playerId].movablePositions.containsKey(movableType)) {
			return Collections.emptyList();
		}
		return playerStatistics[playerId].movablePositions.get(movableType);
	}

	public List<ShortPoint2D> getPositionsOfJoblessBearersForPlayer(byte playerId) {
		return playerStatistics[playerId].joblessBearerPositions;
	}

	public int getCountOfMovablesOfPlayer(IPlayer player, Set<EMovableType> types) {
		byte playerId = player.getPlayerId();
		return types.stream().mapToInt(type -> getPositionsOfMovablesWithTypeForPlayer(playerId, type).size()).sum();
	}

	public int getTotalNumberOfBuildingTypeForPlayer(EBuildingType type, byte playerId) {
		return playerStatistics[playerId].totalBuildingsNumbers[type.ordinal];
	}

	public int getTotalWineCountForPlayer(byte playerId) {
		return playerStatistics[playerId].wineCount;
	}

	public int getNumberOfBuildingTypeForPlayer(EBuildingType type, byte playerId) {
		return playerStatistics[playerId].buildingsNumbers[type.ordinal];
	}

	int getNumberOfNotFinishedBuildingsForPlayer(byte playerId) {
		return playerStatistics[playerId].numberOfNotFinishedBuildings;
	}

	int getNumberOfTotalBuildingsForPlayer(byte playerId) {
		return playerStatistics[playerId].numberOfTotalBuildings;
	}

	public List<ShortPoint2D> getBuildingPositionsOfTypeForPlayer(EBuildingType type, byte playerId) {
		if (!playerStatistics[playerId].buildingPositions.containsKey(type)) {
			return Collections.emptyList();
		}
		return playerStatistics[playerId].buildingPositions.get(type);
	}

	public List<ShortPoint2D> getBuildingPositionsOfTypesForPlayer(EnumSet<EBuildingType> buildingTypes, byte playerId) {
		List<ShortPoint2D> buildingPositions = new Vector<>();
		for (EBuildingType buildingType : buildingTypes) {
			buildingPositions.addAll(getBuildingPositionsOfTypeForPlayer(buildingType, playerId));
		}
		return buildingPositions;
	}

	public Set<ShortPoint2D> getActiveHospitalsForPlayer(byte playerId) {
		return Collections.unmodifiableSet(playerStatistics[playerId].activeHospitals);
	}

	public AiPositions getStonesForPlayer(byte playerId) {
		return playerStatistics[playerId].stones;
	}

	public AiPositions getTreesForPlayer(byte playerId) {
		return playerStatistics[playerId].trees;
	}

	public AiPositions getLandForPlayer(byte playerId) {
		return playerStatistics[playerId].landToBuildOn;
	}

	public boolean blocksWorkingAreaOfOtherBuilding(int x, int y, byte playerId, BuildingVariant building) {
		ECivilisation playerCivilisation = partitionsGrid.getPlayer(playerId).getCivilisation();

		for(EBuildingType type : new EBuildingType[]{FARM, WINEGROWER, RICE_FARM}) {
			BuildingVariant variant = type.getVariant(playerCivilisation);
			if(variant == null) continue;

			if(blocksPositions(x, y, building, variant.getWorkRadius(), playerStatistics[playerId].buildingWorkAreas.get(type))) {
				return true;
			}
		}

		return false;
	}

	private boolean blocksPositions(int x, int y, BuildingVariant newBuilding, int radius, List<ShortPoint2D> positions) {
		if(positions == null) return false;

		for (ShortPoint2D workAreaCenter : positions) {
			for (RelativePoint blockedPoint : newBuilding.getBlockedTiles()) {
				if (workAreaCenter.getOnGridDistTo(blockedPoint.calculatePoint(x, y)) <= radius) {
					return true;
				}
			}
		}
		return false;
	}

	public boolean southIsFreeForPlayer(ShortPoint2D point, byte playerId) {
		return pointIsFreeForPlayer(point.x, (short) (point.y + 12), playerId) &&
				pointIsFreeForPlayer((short) (point.x + 5), (short) (point.y + 12), playerId)
				&& pointIsFreeForPlayer((short) (point.x + 10), (short) (point.y + 12), playerId)
				&& pointIsFreeForPlayer(point.x, (short) (point.y + 6), playerId)
				&& pointIsFreeForPlayer((short) (point.x + 5), (short) (point.y + 6), playerId)
				&& pointIsFreeForPlayer((short) (point.x + 10), (short) (point.y + 6), playerId);
	}

	private boolean pointIsFreeForPlayer(short x, short y, byte playerId) {
		return mainGrid.isInBounds(x, y)
				&& partitionsGrid.getPlayerIdAt(x, y) == playerId
				&& !objectsGrid.isBuildingAt(x, y)
				&& !flagsGrid.isProtected(x, y)
				&& landscapeGrid.isHexAreaOfType(x, y, 2, ELandscapeType.GRASS, ELandscapeType.EARTH);
	}

	public boolean wasFishNearByAtGameStart(ShortPoint2D position, ECivilisation civilisation) {
		return aiMapInformation.wasFishNearByAtGameStart[civilisation.ordinal].get(position.x * partitionsGrid.getWidth() + position.y);
	}

	public ILogicMovable getNearestSwordsmanOf(ShortPoint2D targetPosition, byte playerId) {
		List<ShortPoint2D> soldierPositions = getPositionsOfMovablesWithTypeForPlayer(playerId, SWORDSMAN_L3);
		if (soldierPositions.size() == 0) {
			soldierPositions = getPositionsOfMovablesWithTypeForPlayer(playerId, SWORDSMAN_L2);
		}
		if (soldierPositions.size() == 0) {
			soldierPositions = getPositionsOfMovablesWithTypeForPlayer(playerId, SWORDSMAN_L1);
		}
		if (soldierPositions.size() == 0) {
			return null;
		}

		ShortPoint2D nearestSoldierPosition = detectNearestPointFromList(targetPosition, soldierPositions);
		if (nearestSoldierPosition != null) {
			return movableGrid.getMovableAt(nearestSoldierPosition.x, nearestSoldierPosition.y);
		} else {
			return null;
		}
	}

	public static ShortPoint2D detectNearestPointFromList(ShortPoint2D referencePoint, List<ShortPoint2D> points) {
		if (points.isEmpty()) {
			return null;
		}

		return detectNearestPointsFromList(referencePoint, points, 1).get(0);
	}

	private static List<ShortPoint2D> detectNearestPointsFromList(final ShortPoint2D referencePoint, List<ShortPoint2D> points, int amountOfPointsToDetect) {
		if (amountOfPointsToDetect <= 0) {
			return Collections.emptyList();
		}

		if (points.size() <= amountOfPointsToDetect) {
			return points;
		}

		points.sort(Comparator.comparingInt(o -> o.getOnGridDistTo(referencePoint)));

		return points.subList(0, amountOfPointsToDetect);
	}

	public int getNumberOfMaterialTypeForPlayer(EMaterialType type, byte playerId) {
		if (playerStatistics[playerId].materials == null) {
			return 0;
		}

		return playerStatistics[playerId].materials.getAmountOf(type);
	}

	public MainGrid getMainGrid() {
		return mainGrid;
	}

	public ShortPoint2D getNearestRiverPointInDefaultPartitionFor(ShortPoint2D referencePoint, int searchDistance, AiPositionFilter filter) {
		return getNearestPointInDefaultPartitionOutOfSortedMap(referencePoint, sortedRiversInDefaultPartition, (byte) -1, searchDistance, filter);
	}

	int getNumberOfNotFinishedBuildingTypesForPlayer(EBuildingType buildingType, byte playerId) {
		return getTotalNumberOfBuildingTypeForPlayer(buildingType, playerId) - getNumberOfBuildingTypeForPlayer(buildingType, playerId);
	}

	public AiPositions getRiversForPlayer(byte playerId) {
		return playerStatistics[playerId].rivers;
	}

	private List<IPlayer> getEnemiesOf(IPlayer player) {
		byte teamId = player.getTeamId();
		return players.stream().filter(currPlayer -> currPlayer.getTeamId() != teamId).collect(Collectors.toList());
	}

	public List<IPlayer> getAliveEnemiesOf(IPlayer player) {
		return getEnemiesOf(player).stream().filter(this::isAlive).collect(Collectors.toList());
	}

	/**
	 * @return true if at least one alive enemy owns a finished military building but none of its finished military buildings are reachable
	 *         by land from our base, i.e. that enemy can only be attacked across water. Used to decide whether the AI needs a dockyard.
	 */
	public boolean hasEnemyAcrossWaterOf(IPlayer player) {
		byte playerId = player.getPlayerId();
		if (playerStatistics[playerId].referencePosition == null) {
			return false; // we do not have a base yet, so we cannot judge reachability
		}
		for (IPlayer enemy : getAliveEnemiesOf(player)) {
			boolean hasMilitaryBuilding = false;
			boolean anyLandReachable = false;
			for (ShortPoint2D position : getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, enemy.getPlayerId())) {
				Building building = getBuildingAt(position);
				if (building == null || !building.isConstructionFinished()) {
					continue;
				}
				hasMilitaryBuilding = true;
				if (hasPlayersBlockedPartition(playerId, building.getDoor().x, building.getDoor().y)) {
					anyLandReachable = true;
					break;
				}
			}
			if (hasMilitaryBuilding && !anyLandReachable) {
				return true;
			}
		}
		return false;
	}

	// a land-reachable enemy is a candidate for a sea flank only once we have a real army and still do not clearly outnumber them
	private static final int MIN_ARMY_FOR_FLANK = 12;
	private static final float FLANK_LAND_ASSAULT_EDGE = 1.25f;

	/**
	 * @return true if the enemy owns at least one finished military building whose door is on the same landmass as the given player's base
	 *         (i.e. the enemy can be attacked by walking soldiers).
	 */
	public boolean isEnemyReachableByLand(byte playerId, IPlayer enemy) {
		if (playerStatistics[playerId].referencePosition == null) {
			return false;
		}
		for (ShortPoint2D position : getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, enemy.getPlayerId())) {
			Building building = getBuildingAt(position);
			if (building != null && building.isConstructionFinished()
					&& hasPlayersBlockedPartition(playerId, building.getDoor().x, building.getDoor().y)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return true if there is a land-reachable enemy that the player has a real army against but does not clearly outnumber, so bypassing
	 *         the defended land front by sea (an amphibious flank) may be more effective than a frontal assault. Used to decide whether to
	 *         build a dockyard on maps where the enemy is technically reachable by land.
	 */
	public boolean hasFlankableEnemyOf(IPlayer player) {
		byte playerId = player.getPlayerId();
		int ourSoldiers = getCountOfMovablesOfPlayer(player, EMovableType.SOLDIERS);
		if (ourSoldiers < MIN_ARMY_FOR_FLANK) {
			return false; // no point preparing a flank before we have an army to spare
		}
		for (IPlayer enemy : getAliveEnemiesOf(player)) {
			if (!isEnemyReachableByLand(playerId, enemy)) {
				continue;
			}
			int enemySoldiers = getCountOfMovablesOfPlayer(enemy, EMovableType.SOLDIERS);
			if (ourSoldiers < enemySoldiers * FLANK_LAND_ASSAULT_EDGE) {
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------------------------
	// Cross-water colonization target scan (Phase 0). Pure, deterministic query + value heuristic. Not wired into any decision yet: it
	// lets a later phase discover off-landmass resource deposits (e.g. ore on another island) that the player could only reach by sea,
	// and rate how worthwhile settling one would be. It changes no economy, construction or army behaviour.

	// how far around an off-shore ore tile to look for a water tile a ferry could land next to (mirrors NavalInvasionModule)
	private static final int COLONIZATION_LANDING_SEARCH_RADIUS = 20;
	// weight applied to the summed raw resource amount of a reachable deposit when turning it into a "gain" score
	private static final double COLONIZATION_ORE_GAIN_WEIGHT = 1.0;
	// extra multiplier for a resource the home partition currently has none of, so scarce ore is chased more eagerly
	private static final double COLONIZATION_SCARCE_RESOURCE_WEIGHT = 3.0;
	// cost per tile of sea distance the ferry has to cover from the home coast to the landing site
	private static final double COLONIZATION_FERRY_COST_FACTOR = 0.5;
	// fixed penalty representing the material/economy investment of shipping settlers and raising an outpost
	private static final double COLONIZATION_BUILD_COST = 40.0;
	// penalty per enemy military building already present on the target's landmass (an occupied island is dangerous to settle)
	private static final double COLONIZATION_DEFENSE_RISK_PENALTY = 30.0;

	/**
	 * Finds deposits of the given resource that the player could only reach by sea: ore that is <b>not</b> on the player's home landmass
	 * (land-reachable ore is already handled by normal pioneer expansion) but that has a water tile beside it which is navigable by ship
	 * from a coastal foothold near the player's base. Enumerates candidates from the pre-built {@code sortedResourceTypes} index rather
	 * than scanning the whole map. The result is deterministic (the index and all scans iterate in a stable order).
	 *
	 * @return the positions of sea-reachable off-landmass deposits of {@code resourceType}, in stable order; empty if the player has no
	 *         base yet, no navigable coast, or no such deposit exists.
	 */
	public List<ShortPoint2D> getSeaReachableResourceTargets(byte playerId, EResourceType resourceType) {
		List<ShortPoint2D> targets = new ArrayList<>();
		if (playerStatistics[playerId].referencePosition == null) {
			return targets; // no base yet, so reachability is undefined
		}
		ShortPoint2D homeCoastWater = findHomeCoastWaterFor(playerId);
		if (homeCoastWater == null) {
			return targets; // the player's base does not touch navigable water, so nothing is reachable by sea
		}
		for (ShortPoint2D ore : sortedResourceTypes[resourceType.ordinal]) {
			if (hasPlayersBlockedPartition(playerId, ore.x, ore.y)) {
				continue; // on our own landmass - reachable by land, not a colonization target
			}
			if (findSeaReachableLandingNear(ore, homeCoastWater) != null) {
				targets.add(ore);
			}
		}
		return targets;
	}

	/**
	 * @return a water tile touching the player's territory that is navigable by ship (a sea partition) and closest to the base, or null if
	 *         the player's land does not border navigable water. This is the notional embarkation point a ferry would leave from (the same
	 *         idiom {@link jsettlers.ai.construction.DockyardConstructionPositionFinder} uses to find a shore for a dock).
	 */
	private ShortPoint2D findHomeCoastWaterFor(byte playerId) {
		ShortPoint2D reference = playerStatistics[playerId].referencePosition;
		if (reference == null) {
			return null;
		}
		ShortPoint2D best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ShortPoint2D land : getLandForPlayer(playerId)) {
			for (EDirection direction : EDirection.VALUES) {
				int wx = direction.gridDeltaX + land.x;
				int wy = direction.gridDeltaY + land.y;
				if (!mainGrid.isInBounds(wx, wy) || !landscapeGrid.getLandscapeTypeAt(wx, wy).isWater) {
					continue;
				}
				if (landscapeGrid.isBlockedFor(wx, wy, true)) {
					continue; // not part of a sea partition (e.g. a landlocked pond) - a ferry could not sail from here
				}
				int distance = reference.getOnGridDistTo(new ShortPoint2D(wx, wy));
				if (distance < bestDistance) {
					bestDistance = distance;
					best = new ShortPoint2D(wx, wy);
				}
			}
		}
		return best;
	}

	/**
	 * @return the nearest water tile to {@code target} that is navigable by ship from {@code dockWater}, or null if none is within range.
	 *         Unloading a ferry there would drop settlers on the land next to it (see FerryMovable.unloadFerry). Mirrors
	 *         {@link jsettlers.ai.army.NavalInvasionModule}'s landing search.
	 */
	private ShortPoint2D findSeaReachableLandingNear(ShortPoint2D target, ShortPoint2D dockWater) {
		short width = mainGrid.getWidth();
		short height = mainGrid.getHeight();
		return HexGridArea.stream(target.x, target.y, 1, COLONIZATION_LANDING_SEARCH_RADIUS)
				.filterBounds(width, height)
				.filter((x, y) -> landscapeGrid.getLandscapeTypeAt(x, y).isWater)
				.filter((x, y) -> landscapeGrid.isReachable(dockWater.x, dockWater.y, x, y, true))
				.getFirst()
				.orElse(null);
	}

	/**
	 * Public accessor used by {@link jsettlers.ai.army.ColonizationModule} (Phase 1) to find where a ferry should unload pioneers next to a
	 * sea-reachable deposit. It reuses the exact partition/landing logic behind {@link #getSeaReachableResourceTargets(byte, EResourceType)}
	 * so that any target the scan returns has a consistent, ship-navigable landing tile.
	 *
	 * @return the nearest water tile beside {@code target} that is navigable by ship from the player's home coast (unloading a ferry there
	 *         drops the settlers on the adjacent land), or null if the player has no navigable coast or no landing is in range.
	 */
	public ShortPoint2D getSeaReachableLandingNear(byte playerId, ShortPoint2D target) {
		ShortPoint2D homeCoastWater = findHomeCoastWaterFor(playerId);
		if (homeCoastWater == null) {
			return null;
		}
		return findSeaReachableLandingNear(target, homeCoastWater);
	}

	/**
	 * A pure value heuristic that rates how worthwhile settling a given off-landmass deposit would be. It is not consumed by any decision
	 * yet (Phase 0). Higher is better:
	 * <p>
	 * {@code value = ore_gain - ferry_cost - build_cost - defense_risk}
	 * <ul>
	 * <li>ore_gain: summed raw resource amount over the deposit, boosted when the home partition currently has none of this resource;</li>
	 * <li>ferry_cost: sea distance from the home coast to the landing tile, times a small factor;</li>
	 * <li>build_cost: a fixed outpost-investment penalty;</li>
	 * <li>defense_risk: a penalty per enemy military building already on the deposit's landmass.</li>
	 * </ul>
	 *
	 * @param reachableOre
	 *            the deposit to rate, typically a subset of {@link #getSeaReachableResourceTargets(byte, EResourceType)}.
	 * @return the score, or {@link Double#NEGATIVE_INFINITY} if the deposit is empty or not actually sea-reachable.
	 */
	public double rateSeaReachableResourceTarget(byte playerId, EResourceType resourceType, List<ShortPoint2D> reachableOre) {
		if (reachableOre.isEmpty()) {
			return Double.NEGATIVE_INFINITY;
		}
		ShortPoint2D homeCoastWater = findHomeCoastWaterFor(playerId);
		if (homeCoastWater == null) {
			return Double.NEGATIVE_INFINITY;
		}
		ShortPoint2D representative = reachableOre.get(0);
		ShortPoint2D landing = findSeaReachableLandingNear(representative, homeCoastWater);
		if (landing == null) {
			return Double.NEGATIVE_INFINITY;
		}

		double oreAmount = 0;
		for (ShortPoint2D ore : reachableOre) {
			oreAmount += landscapeGrid.getResourceAmountAt(ore.x, ore.y);
		}
		double shortageWeight = resourceCountOfPlayer(resourceType, playerId) <= 0 ? COLONIZATION_SCARCE_RESOURCE_WEIGHT : 1.0;
		double oreGain = oreAmount * COLONIZATION_ORE_GAIN_WEIGHT * shortageWeight;

		double ferryCost = homeCoastWater.getOnGridDistTo(landing) * COLONIZATION_FERRY_COST_FACTOR;
		double defenseRisk = colonizationDefenseRisk(playerId, representative);

		return oreGain - ferryCost - COLONIZATION_BUILD_COST - defenseRisk;
	}

	/** @return a penalty proportional to the number of finished-or-not military buildings alive enemies own on {@code target}'s landmass. */
	private double colonizationDefenseRisk(byte playerId, ShortPoint2D target) {
		int targetPartition = partitionsGrid.getPartitionIdAt(target.x, target.y);
		int enemyMilitaryBuildings = 0;
		for (IPlayer enemy : getAliveEnemiesOf(partitionsGrid.getPlayer(playerId))) {
			for (ShortPoint2D position : getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, enemy.getPlayerId())) {
				if (partitionsGrid.getPartitionIdAt(position.x, position.y) == targetPartition) {
					enemyMilitaryBuildings++;
				}
			}
		}
		return enemyMilitaryBuildings * COLONIZATION_DEFENSE_RISK_PENALTY;
	}

	public static ShortPoint2D calculateAveragePointFromList(List<ShortPoint2D> points) {
		int averageX = 0;
		int averageY = 0;
		for (ShortPoint2D point : points) {
			averageX += point.x;
			averageY += point.y;
		}
		return new ShortPoint2D(averageX / points.size(), averageY / points.size());
	}

	public AiPositions getEnemiesInTownOf(byte playerId) {
		return playerStatistics[playerId].enemyTroopsInTown;
	}

	public IMaterialProductionSettings getMaterialProduction(byte playerId) {
		return playerStatistics[playerId].materialProduction;
	}

	public ShortPoint2D getPositionOfPartition(byte playerId) {
		return playerStatistics[playerId].referencePosition;
	}

	public AiPositions getBorderIngestibleByPioneersOf(byte playerId) {
		return playerStatistics[playerId].borderIngestibleByPioneers;
	}

	public AiPositions getOtherPartitionBorderOf(byte playerId) {
		return playerStatistics[playerId].otherPartitionBorder;
	}

	public boolean isAlive(IPlayer player) {
		return player.getWinState() != EWinState.LOST;
	}

	public boolean isAlive(byte playerId) {
		return partitionsGrid.getPlayer(playerId).getWinState() != EWinState.LOST;
	}

	public long resourceCountInDefaultPartition(EResourceType resourceType) {
		return defaultPartitionResources.resourceCount[resourceType.ordinal];
	}

	public long resourceCountOfPlayer(EResourceType resourceType, byte playerId) {
		return playerStatistics[playerId].partitionResources.resourceCount[resourceType.ordinal];
	}

	List<ShortPoint2D> threatenedBorderOf(byte playerId) {
		if (playerStatistics[playerId].threatenedBorder == null) {
			AiPositions borderOfOtherPlayers = new AiPositions();

			players.stream()
					.filter(currPlayer -> currPlayer.playerId != playerId)
					.filter(this::isAlive)
					.forEach(currPlayer -> borderOfOtherPlayers.addAllNoCollision(getBorderIngestibleByPioneersOf(currPlayer.playerId)));

			playerStatistics[playerId].threatenedBorder = new ArrayList<>();
			AiPositions myBorder = getBorderIngestibleByPioneersOf(playerId);

			for (int i = 0; i < myBorder.size(); i += 10) {
				ShortPoint2D myBorderPosition = myBorder.get(i);
				if (!partitionsGrid.isEnforcedByTower(myBorderPosition.x, myBorderPosition.y)
						&& borderOfOtherPlayers.getNearestPoint(myBorderPosition, CommonConstants.TOWER_RADIUS) != null) {
					playerStatistics[playerId].threatenedBorder.add(myBorderPosition);
				}
			}
		}
		return playerStatistics[playerId].threatenedBorder;
	}

	public AiPositions getStonesNearBy(byte playerId) {
		return playerStatistics[playerId].stonesNearBy;
	}

	public long getGrassTilesOf(byte playerId) {
		return playerStatistics[playerId].partitionResources.grassCount;
	}

	public long getRemainingGrassTiles(AiStatistics aiStatistics, IPlayer player) {
		byte playerId = player.getPlayerId();
		ECivilisation civilisation = player.getCivilisation();

		long remainingGrass = playerStatistics[playerId].partitionResources.grassCount;
		for (EBuildingType buildingType : EBuildingType.VALUES) {
			BuildingVariant building = buildingType.getVariant(civilisation);

			if(building != null && !building.isMine()) {
				remainingGrass -= building.getProtectedTiles().length * (long)aiStatistics.getTotalNumberOfBuildingTypeForPlayer(buildingType, playerId);
			}
		}
		return remainingGrass;
	}

	public int[] getBuildingCounts(IPlayer player) {
		return aiMapInformation.getBuildingCounts(playerStatistics[player.getPlayerId()], player);
	}
}