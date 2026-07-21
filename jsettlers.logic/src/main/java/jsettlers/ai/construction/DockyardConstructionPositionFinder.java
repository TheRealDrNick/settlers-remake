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
package jsettlers.ai.construction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.map.shapes.HexGridArea;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.buildings.IDockBuilding;
import jsettlers.logic.map.grid.landscape.LandscapeGrid;

/**
 * Finds a construction position for a dock building (DOCKYARD / HARBOR): a buildable tile within the player's territory that is close to
 * open water, so that a dock - and therefore ships - can be placed afterwards. Positions are scored by the distance to the nearest water
 * tile (closer is better) so the dockyard ends up near the shore and well within {@link IDockBuilding#MAXIMUM_DOCKYARD_DISTANCE} of a coast.
 *
 * @author jsettlers naval AI
 */
public class DockyardConstructionPositionFinder extends ConstructionPositionFinder {

	// only consider building spots whose nearest water is at most this far away. Kept well below MAXIMUM_DOCKYARD_DISTANCE (25) so that a
	// valid dock can reliably be placed later, and to bound the search cost.
	private static final int MAX_WATER_SEARCH_RADIUS = 12;

	private final EBuildingType buildingType;

	protected DockyardConstructionPositionFinder(Factory factory, EBuildingType buildingType) {
		super(factory);
		this.buildingType = buildingType;
	}

	@Override
	public ShortPoint2D findBestConstructionPosition() {
		LandscapeGrid landscapeGrid = aiStatistics.getMainGrid().getLandscapeGrid();
		short width = aiStatistics.getMainGrid().getWidth();
		short height = aiStatistics.getMainGrid().getHeight();

		List<ScoredConstructionPosition> scoredConstructionPositions = new ArrayList<>();
		for (ShortPoint2D point : aiStatistics.getLandForPlayer(playerId)) {
			if (!constructionMap.canConstructAt(point.x, point.y, buildingType, playerId)) {
				continue;
			}
			// find the nearest water tile - HexGridArea iterates outwards, so the first match is (approximately) the closest
			Optional<ShortPoint2D> nearestWater = HexGridArea.stream(point.x, point.y, 1, MAX_WATER_SEARCH_RADIUS)
					.filterBounds(width, height)
					.filter((x, y) -> landscapeGrid.getLandscapeTypeAt(x, y).isWater)
					.getFirst();
			if (nearestWater.isPresent()) {
				scoredConstructionPositions.add(new ScoredConstructionPosition(point, point.getOnGridDistTo(nearestWater.get())));
			}
		}

		return ScoredConstructionPosition.detectPositionWithLowestScore(scoredConstructionPositions);
	}
}
