package com.magnaboy;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

public class CitizensOverlay extends Overlay {
	private final Citizens2Plugin plugin;
	private final ModelOutlineRenderer modelOutlineRenderer;

	Font overheadFont = FontManager.getRunescapeBoldFont();

	@Inject
	public CitizensOverlay(Citizens2Plugin plugin, ModelOutlineRenderer modelOutlineRenderer) {
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		this.plugin = plugin;
	}

	private void renderText(Graphics2D graphics, LocalPoint lp, String text, Color color) {
		if (!plugin.IS_DEVELOPMENT || lp == null) {
			return;
		}

		Client client = plugin.client;
		if (client == null) {
			return;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null) {
			return;
		}

		Font overheadFont = FontManager.getRunescapeSmallFont();
		graphics.setFont(overheadFont);

		Point p = Perspective.localToCanvas(client, lp, worldView.getPlane(), 0);
		if (p == null) {
			return;
		}
		FontMetrics metrics = graphics.getFontMetrics(overheadFont);
		Point shiftedP = new Point(p.getX() - (metrics.stringWidth(text) / 2), p.getY());
		OverlayUtil.renderTextLocation(graphics, shiftedP, text, color);
	}

	private void highlightTile(Graphics2D graphics, LocalPoint lp, Color color) {
		if (lp == null) {
			return;
		}
		if (!plugin.IS_DEVELOPMENT) {
			return;
		}

		Client client = plugin.client;
		if (client == null) {
			return;
		}

		final Polygon poly = Perspective.getCanvasTilePoly(client, lp);
		if (poly != null) {
			OverlayUtil.renderPolygon(graphics, poly, color);
		}
	}

	private void highlightTile(Graphics2D graphics, WorldPoint wp, Color color) {
		if (!plugin.IS_DEVELOPMENT) {
			return;
		}

		Client client = plugin.client;
		if (client == null) {
			return;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null) {
			return;
		}

		LocalPoint lp = LocalPoint.fromWorld(worldView, wp);
		if (lp == null) {
			return;
		}
		final Polygon poly = Perspective.getCanvasTilePoly(client, lp);
		if (poly != null) {
			OverlayUtil.renderPolygon(graphics, poly, color);
		}
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (Citizens2Plugin.shuttingDown) {
			return null;
		}

		Client client = plugin.client;
		if (client == null) {
			return null;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null) {
			return null;
		}

		if (CitizenPanel.selectedPosition != null) {
			Color selectedColor = new Color(0, 255, 255, 200);
			highlightTile(graphics, CitizenPanel.selectedPosition, selectedColor);
			LocalPoint lp = LocalPoint.fromWorld(worldView, CitizenPanel.selectedPosition);
			if (plugin.IS_DEVELOPMENT && lp != null) {
				renderText(graphics, lp, "Selected Tile", selectedColor);
			}
		}

		if (CitizenPanel.selectedEntity != null) {
			final int outlineWidth = 4;
			modelOutlineRenderer.drawOutline(CitizenPanel.selectedEntity.rlObject, outlineWidth, Color.cyan, outlineWidth - 2);
		}

		CitizenRegion.forEachActiveEntity((entity) -> {
			if (!entity.isCitizen()) {
				return;
			}

			Citizen<?> citizen = (Citizen<?>) entity;
			LocalPoint localLocation = citizen.getLocalLocation();

			if (!citizen.shouldRender() || localLocation == null) {
				return;
			}

			// Render remarks
			if (citizen.activeRemark != null) {
				Model model = citizen.rlObject.getModel();
				if (model == null) {
					return;
				}

				Point p = Perspective.localToCanvas(client, localLocation, worldView.getPlane(), model.getModelHeight());
				if (p != null) {
					graphics.setFont(overheadFont);
					FontMetrics metrics = graphics.getFontMetrics(overheadFont);
					Point shiftedP = new Point(p.getX() - (metrics.stringWidth(citizen.activeRemark) / 2), p.getY());
					OverlayUtil.renderTextLocation(graphics, shiftedP, citizen.activeRemark,
						JagexColors.YELLOW_INTERFACE_TEXT);
				}
			}

			if (plugin.IS_DEVELOPMENT && citizen.distanceToPlayer() < 15) {
				Model model = citizen.rlObject.getModel();
				int modelHeight = model == null ? 0 : model.getModelHeight();
				String extraString = "";
				if (citizen.entityType == EntityType.ScriptedCitizen) {
					ScriptedCitizen scriptedCitizen = (ScriptedCitizen) citizen;
					if (scriptedCitizen.currentAction != null && scriptedCitizen.currentAction.action != null) {
						extraString = scriptedCitizen.currentAction.action + " ";
					}
				}
				String debugText = citizen.debugName() + " " + extraString + "H:" + modelHeight + " ";
				renderText(graphics, localLocation, debugText, JagexColors.YELLOW_INTERFACE_TEXT);
				Citizen.Target target = citizen.getCurrentTarget();
				if (target != null) {
					highlightTile(graphics, target.localDestinationPosition, new Color(235, 150, 52));
				}
			}
		});

		return null;
	}
}
