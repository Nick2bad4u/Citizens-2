package com.magnaboy;

import static com.magnaboy.Util.worldPointToShortCoord;
import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.SceneryInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ItemEvent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

class CitizenPanel extends PluginPanel {
	private final static String RELOAD_BUTTON_READY = "Reload All Entites";
	public static WorldPoint selectedPosition;
	public static Entity<?> selectedEntity;
	public WorldPoint wanderRegionBL;
	public WorldPoint wanderRegionTR;
	public JLabel editingTargetLabel;
	public JButton updateButton;
	public JButton deleteButton;
	public JLabel reloadWarning;
	public JCheckBox manualFieldsToggle;
	private Citizens2Plugin plugin;
	private JLabel label;
	// Editor Panel Fields
	private HashSet<JComponent> allElements;
	private JButton reloadButton;
	private JButton saveChangesButton;
	private JButton spawnButton;
	private JLabel selectedPositionLbl;
	private JTextField entityNameField;
	private JComboBox<EntityType> entityTypeSelection;
	private JComboBox<AnimationID> animIdIdleSelect;
	private JComboBox<AnimationID> animIdMoveSelect;
	private JTextField modelIdsField;
	private JTextField npcIdImportField;
	private JTextField recolorFindField;
	private JTextField recolorReplaceField;
	private JComboBox<CardinalDirection> orientationField;
	private JTextField examineTextField;
	private JTextField remarksField;
	private JTextField scaleFieldX;
	private JTextField scaleFieldY;
	private JTextField scaleFieldZ;
	private JTextField translateFieldX;
	private JTextField translateFieldY;
	private JTextField translateFieldZ;
	private JTextField heightOffsetField;
	private JButton selectWanderBL;
	private JButton selectWanderTR;
	private JTextField manualAnimIdIdleSelect;
	private JTextField manualAnimIdMoveSelect;

	// End Editor Fields

	public void init(Citizens2Plugin plugin) {
		this.plugin = plugin;
		final JPanel layoutPanel = new JPanel();
		layoutPanel.setLayout(new GridBagLayout());
		add(layoutPanel, BorderLayout.CENTER);

		label = new JLabel();
		label.setHorizontalAlignment(SwingConstants.CENTER);

		// DEV ONLY
		allElements = new HashSet<>();
		if (plugin.IS_DEVELOPMENT) {
			addEditorComponents(layoutPanel);
			entityTypeChanged();
		}
		update();
	}

	public void update() {
		if (!plugin.IS_DEVELOPMENT) {
			return;
		}
		UpdateEditorFields();

		AtomicInteger activeEntities = new AtomicInteger();
		AtomicInteger inactiveEntities = new AtomicInteger();

		CitizenRegion.forEachEntity((entity) -> {
			if (entity == null) {
				return;
			}
			if (entity.isActive()) {
				activeEntities.addAndGet(1);
			} else {
				inactiveEntities.addAndGet(1);
			}
		});

		int totalEntities = activeEntities.get() + inactiveEntities.get();
		label.setText(activeEntities + "/" + totalEntities + " entities are active");

		UpdateEditorFields();
	}

	private void UpdateEditorFields() {
		GameState state = plugin.client.getGameState();

		if (state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR) {
			selectedPosition = null;
		}
		int dirtySize = CitizenRegion.dirtyRegionCount();

		reloadButton.setEnabled(state == GameState.LOGGED_IN);
		selectedPositionLbl.setText(selectedPosition == null ? "N/A" : worldPointToShortCoord(selectedPosition));

		String errorMessage = validateFields();
		boolean valid = errorMessage.isEmpty();
		boolean canSpawn = state == GameState.LOGGED_IN && valid;
		spawnButton.setEnabled(canSpawn);
		spawnButton.setText(canSpawn ? "Spawn Entity" : "Can't Spawn: " + errorMessage);

		saveChangesButton.setEnabled(true);
		saveChangesButton.setText("Save Changes");

		if (selectedEntity != null && !CitizenPanel.selectedEntity.isActive()) {
			selectedEntity = null;
		}

		updateButton.setVisible(selectedEntity != null);

		if (selectedEntity instanceof Citizen) {
			editingTargetLabel.setText("Editing: " + selectedEntity.name);
		} else {
			editingTargetLabel.setText("Editing: Scenery Object");
		}
		editingTargetLabel.setVisible(selectedEntity != null);
		deleteButton.setVisible(selectedEntity != null);

		reloadWarning.setVisible(dirtySize > 0);

		selectWanderBL.setText(wanderRegionBL == null ? "Select BL" : Util.worldPointToShortCoord(wanderRegionBL));
		selectWanderTR.setText(wanderRegionTR == null ? "Select TR" : Util.worldPointToShortCoord(wanderRegionTR));
	}

	private String validateFields() {
		if (selectedPosition == null) {
			return "No Position Selected";
		}

		EntityType selectedType = (EntityType) entityTypeSelection.getSelectedItem();

		if (fieldEmpty(entityNameField) && selectedType != EntityType.Scenery) {
			return "Empty Name";
		}

		if (fieldEmpty(modelIdsField)) {
			return "No Model IDs";
		}

		if (csvToIntArray(modelIdsField.getText()).length == 0) {
			return "Invalid Model Ids";
		}

		if (!fieldEmpty(heightOffsetField)) {
			try {
				Integer.parseInt(heightOffsetField.getText().trim());
			} catch (Exception ignored) {
				return "Invalid Height Offset";
			}
		}

		if (csvToIntArray(recolorFindField.getText()).length !=
			csvToIntArray(recolorReplaceField.getText()).length) {
			return "Model Color Mismatch";
		}

		if (manualFieldsToggle.isSelected()) {
			if (parseIntOrNull(manualAnimIdIdleSelect) == null) {
				return "Invalid Idle Animation ID";
			}

			if (selectedType != EntityType.Scenery) {
				boolean moveRequired = selectedType == EntityType.WanderingCitizen || selectedType == EntityType.ScriptedCitizen;
				if (moveRequired && parseIntOrNull(manualAnimIdMoveSelect) == null) {
					return "Invalid Move Animation ID";
				}

				if (!fieldEmpty(manualAnimIdMoveSelect) && parseIntOrNull(manualAnimIdMoveSelect) == null) {
					return "Invalid Move Animation ID";
				}
			}
		}

		if (selectedType == EntityType.WanderingCitizen) {
			if (wanderRegionBL == null || wanderRegionTR == null) {
				return "Incomplete Wander Region";
			}
		}
		return "";
	}

	private boolean fieldEmpty(JTextField f) {
		return f.getText() == null || f.getText().trim().isEmpty();
	}

	// DEV ONLY
	private void addEditorComponents(JPanel layoutPanel) {

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.BOTH;
		gbc.insets = new Insets(0, 0, 7, 2);
		gbc.weightx = 0.5;
		gbc.gridwidth = GridBagConstraints.REMAINDER;

		// Active Entities
		{
			gbc.gridx = 0;
			gbc.gridy = 0;
			layoutPanel.add(label, gbc);
		}

		// Reload Entities
		{
			gbc.gridy++;

			gbc.gridx = 0;
			reloadButton = new JButton();
			reloadButton.setText(RELOAD_BUTTON_READY);
			reloadButton.setHorizontalAlignment(SwingConstants.CENTER);
			reloadButton.setFocusable(false);

			reloadButton.addActionListener(e -> {
				selectedEntity = null;
				plugin.reload();
				UpdateEditorFields();
			});
			layoutPanel.add(reloadButton, gbc);

			gbc.gridy++;
			reloadWarning = new JLabel("Unsaved Changes Will Be Lost");
			reloadWarning.setFont(FontManager.getRunescapeSmallFont());
			reloadWarning.setBorder(new EmptyBorder(0, 0, 0, 0));
			reloadWarning.setForeground(Color.ORANGE);
			reloadWarning.setVerticalAlignment(SwingConstants.NORTH);
			reloadWarning.setHorizontalAlignment(SwingConstants.CENTER);
			reloadWarning.setVisible(false);
			layoutPanel.add(reloadWarning, gbc);

		}

		// Editing Target
		{
			gbc.gridy++;
			editingTargetLabel = new JLabel();
			editingTargetLabel.setHorizontalAlignment(SwingConstants.CENTER);
			editingTargetLabel.setForeground(Color.orange);
			editingTargetLabel.setVisible(false);
			layoutPanel.add(editingTargetLabel, gbc);
		}

		// Selected Position Label
		{
			gbc.gridy++;

			gbc.gridx = 0;
			selectedPositionLbl = createLabeledComponent(new JLabel(), "Selected Position", layoutPanel, gbc);
		}

		// Name Field
		{
			gbc.gridy++;

			gbc.gridx = 0;
			entityNameField = createLabeledComponent(new JTextField(), "Entity Name", layoutPanel, gbc);
		}

		// Examine Text
		{
			gbc.gridy++;

			gbc.gridx = 0;
			examineTextField = createLabeledComponent(new JTextField(), "Examine Text", layoutPanel, gbc);
			examineTextField.setText("A Citizen of Gielinor");
		}

		// Entity Type
		{
			gbc.gridy++;

			gbc.gridx = 0;

			entityTypeSelection = createLabeledComponent(new JComboBox<>(EntityType.values()), "Entity Type", layoutPanel, gbc);
			entityTypeSelection.setFocusable(false);
			entityTypeSelection.addActionListener(e -> {
				entityTypeChanged();
			});
		}

		// Cardinal Direction
		{
			gbc.gridy++;

			gbc.gridx = 0;
			orientationField = createLabeledComponent(new JComboBox<>(CardinalDirection.values()), "Base Orientation", layoutPanel, gbc);
			orientationField.setSelectedItem(CardinalDirection.South);
			orientationField.setFocusable(false);
		}

		// Animations
		{
			gbc.gridy++;
			gbc.gridwidth = 2;

			gbc.insets = new Insets(15, 0, 0, 2);
			manualFieldsToggle = new JCheckBox("Manual Animation IDs");
			manualFieldsToggle.setFont(FontManager.getRunescapeSmallFont());
			manualFieldsToggle.setHorizontalAlignment(SwingConstants.RIGHT);
			layoutPanel.add(manualFieldsToggle, gbc);

			manualFieldsToggle.addItemListener(e -> {
				if (e.getStateChange() == ItemEvent.SELECTED || e.getStateChange() == ItemEvent.DESELECTED) {
					boolean checked = manualFieldsToggle.isSelected();

					animIdIdleSelect.getParent().setVisible(!checked);
					if (entityTypeSelection.getSelectedItem() != EntityType.Scenery) {
						animIdMoveSelect.getParent().setVisible(!checked);
					}

					manualAnimIdIdleSelect.getParent().setVisible(checked);
					if (entityTypeSelection.getSelectedItem() != EntityType.Scenery) {
						manualAnimIdMoveSelect.getParent().setVisible(checked);
					}
				}
			});

			AnimationID[] animIds = AnimationID.values();
			Arrays.sort(animIds, Comparator.comparing(Enum::name));

			gbc.gridy++;
			gbc.gridx = 0;
			gbc.gridwidth = 2;
			gbc.insets = new Insets(0, 0, 7, 2);
			animIdIdleSelect = createLabeledComponent(new JComboBox<>(animIds), "Idle Animation", layoutPanel, gbc);
			animIdIdleSelect.setSelectedItem(AnimationID.HumanIdle);
			animIdIdleSelect.setFocusable(false);

			gbc.gridy++;
			gbc.gridwidth = 2;
			animIdMoveSelect = createLabeledComponent(new JComboBox<>(animIds), "Move Animation", layoutPanel, gbc);
			animIdMoveSelect.setSelectedItem(AnimationID.HumanWalk);
			animIdMoveSelect.setFocusable(false);

			gbc.gridy++;
			gbc.gridwidth = 2;
			manualAnimIdIdleSelect = createLabeledComponent(new JTextField(), "Idle Animation", layoutPanel, gbc);
			manualAnimIdIdleSelect.setToolTipText("Raw animation ID (e.g. 4193)");
			manualAnimIdIdleSelect.getParent().setVisible(false);

			gbc.gridy++;
			gbc.gridwidth = 2;
			manualAnimIdMoveSelect = createLabeledComponent(new JTextField(), "Move Animation", layoutPanel, gbc);
			manualAnimIdMoveSelect.setToolTipText("Raw animation ID (e.g. 4194)");
			manualAnimIdMoveSelect.getParent().setVisible(false);
		}

		// Models
		{
			gbc.gridy++;
			gbc.gridx = 0;
			modelIdsField = createLabeledComponent(new JTextField(), "Model Ids", layoutPanel, gbc);
			modelIdsField.setToolTipText("Integers only, separated by commas");

			gbc.gridy++;
			gbc.gridx = 0;
			gbc.gridwidth = 2;
			npcIdImportField = new JTextField();
			npcIdImportField.setToolTipText("NPC ID to import model IDs and recolors (e.g. 4329)");
			JButton importNpcIdButton = new JButton("Import NPC ID");
			importNpcIdButton.setFocusable(false);
			importNpcIdButton.addActionListener(e -> importNpcDefinitionFromField());
			createLabeledMultiComponent("NPC Definition", layoutPanel, gbc, npcIdImportField, importNpcIdButton);

			gbc.gridy++;
			gbc.gridx = 0;
			gbc.gridwidth = 2;

			JButton debugSpawnModelPartsButton = new JButton("DEBUG: Split Model IDs");
			debugSpawnModelPartsButton.setFocusable(false);
			debugSpawnModelPartsButton.addActionListener(e -> {
				WorldPoint debugBase = selectedPosition;
				if (selectedEntity != null && selectedEntity.getWorldLocation() != null) {
					debugBase = selectedEntity.getWorldLocation();
				}

				plugin.debugSpawnModelParts(
					debugBase,
					csvToIntArray(modelIdsField.getText()),
					csvToIntArray(recolorFindField.getText()),
					csvToIntArray(recolorReplaceField.getText())
				);
			});

			JButton debugClearModelPartsButton = new JButton("DEBUG: Clear Split Parts");
			debugClearModelPartsButton.setFocusable(false);
			debugClearModelPartsButton.addActionListener(e -> plugin.clearDebugModelParts());

			createLabeledMultiComponent("Model ID Debug", layoutPanel, gbc, debugSpawnModelPartsButton, debugClearModelPartsButton);
		}

		// Remarks
		{
			gbc.gridy++;
			gbc.gridx = 0;
			gbc.gridwidth = 2;
			remarksField = createLabeledComponent(new JTextField(), "Remarks", layoutPanel, gbc);
			remarksField.setToolTipText("Phrases, separated by commas");
		}

		// Model Recolors
		{
			gbc.gridy++;
			gbc.gridwidth = 1;
			recolorFindField = createLabeledComponent(new JTextField(), "Find Model Colors", layoutPanel, gbc);
			recolorFindField.setToolTipText("Integers only, separated by commas");

			gbc.gridx = 1;
			recolorReplaceField = createLabeledComponent(new JTextField(), "Replace Model Colors", layoutPanel, gbc);
			recolorReplaceField.setToolTipText("Integers only, separated by commas");
		}

		// Scale
		{
			gbc.gridy++;
			gbc.gridwidth = 2;
			gbc.gridx = 0;
			scaleFieldX = new JTextField();
			scaleFieldY = new JTextField();
			scaleFieldZ = new JTextField();
			createLabeledMultiComponent("Scale", layoutPanel, gbc, scaleFieldX, scaleFieldY, scaleFieldZ);
		}

		// Translation
		{
			gbc.gridy++;
			gbc.gridwidth = 2;
			gbc.gridx = 0;
			translateFieldX = new JTextField();
			translateFieldY = new JTextField();
			translateFieldZ = new JTextField();
			createLabeledMultiComponent("Translation", layoutPanel, gbc, translateFieldX, translateFieldY, translateFieldZ);
		}

		// Height Offset
		{
			gbc.gridy++;
			gbc.gridx = 0;
			gbc.gridwidth = 2;
			heightOffsetField = createLabeledComponent(new JTextField(), "Height Offset", layoutPanel, gbc);
			heightOffsetField.setToolTipText("Optional per-entity vertical offset. Positive raises, negative lowers.");
		}

		// Wander Region
		{
			gbc.gridy++;
			gbc.gridwidth = 2;
			gbc.gridx = 0;

			selectWanderBL = new JButton();
			selectWanderBL.setText("Select BL");
			selectWanderBL.setFocusable(false);
			selectWanderBL.addActionListener(e -> {
				wanderRegionBL = selectedPosition;
				selectWanderBL.setText(Util.worldPointToShortCoord(selectedPosition));
			});

			selectWanderTR = new JButton();
			selectWanderTR.setText("Select TR");
			selectWanderTR.setFocusable(false);
			selectWanderTR.addActionListener(e -> {
				wanderRegionTR = selectedPosition;
				selectWanderTR.setText(Util.worldPointToShortCoord(selectedPosition));
			});

			createLabeledMultiComponent("Wander Region", layoutPanel, gbc, selectWanderBL, selectWanderTR);
		}

		// Spawn/Save Button
		{
			gbc.gridy++;
			gbc.gridx = 0;
			spawnButton = new JButton();
			spawnButton.setText("Spawn Entity");
			spawnButton.setFocusable(false);
			spawnButton.addActionListener(e -> {
				if (entityTypeSelection.getSelectedItem() == EntityType.Scenery) {
					SceneryInfo info = buildSceneryInfo(selectedPosition);
					Scenery scenery = CitizenRegion.spawnSceneryFromPanel(info);
					selectedEntity = scenery;
				} else {
					CitizenInfo info = buildCitizenInfo(selectedPosition.getRegionID(), selectedPosition);
					Citizen<?> citizen = CitizenRegion.spawnCitizenFromPanel(info);
					selectedEntity = citizen;
				}
				selectedPosition = null;
				update();
			});
			layoutPanel.add(spawnButton, gbc);

			gbc.gridy++;
			gbc.gridx = 0;
			updateButton = new JButton();
			updateButton.setText("Update Entity");
			updateButton.setFocusable(false);
			updateButton.addActionListener(e -> {
				if (selectedEntity == null) {
					return;
				}

				WorldPoint location = selectedEntity.getWorldLocation();
				if (location == null) {
					return;
				}

				if (selectedEntity.isCitizen()) {
					CitizenInfo info = buildCitizenInfo(selectedEntity.regionId, location);
					info.uuid = selectedEntity.uuid;
					CitizenRegion.updateEntity(info);
					Entity<?> refreshed = CitizenRegion.getEntity(info.regionId, info.uuid);
					if (refreshed != null) {
						selectedEntity = null;
						setSelectedEntity(refreshed);
					}
				} else if (selectedEntity.entityType == EntityType.Scenery) {
					SceneryInfo info = buildSceneryInfo(location);
					info.uuid = selectedEntity.uuid;
					CitizenRegion.updateEntity(info);
					Entity<?> refreshed = CitizenRegion.getEntity(info.regionId, info.uuid);
					if (refreshed != null) {
						selectedEntity = null;
						setSelectedEntity(refreshed);
					}
				}

				CitizenRegion.updateAllEntities();
				update();
			});
			layoutPanel.add(updateButton, gbc);
		}

		// Delete Button
		{
			gbc.gridy++;
			gbc.gridx = 0;
			deleteButton = new JButton();
			deleteButton.setText("Delete Entity");
			deleteButton.setFocusable(false);
			deleteButton.setVisible(false);
			deleteButton.setBackground(new Color(135, 58, 58));
			deleteButton.addActionListener(e -> {
				CitizenRegion.removeEntityFromRegion(selectedEntity);
				selectedEntity.despawn();
			});
			layoutPanel.add(deleteButton, gbc);
		}

		// Save Changes
		{
			gbc.gridy++;

			saveChangesButton = new JButton();
			saveChangesButton.setText("Save Changes");
			saveChangesButton.setFocusable(false);
			saveChangesButton.addActionListener(e -> {
				CitizenRegion.saveDirtyRegions();
			});

			gbc.gridx = 0;
			layoutPanel.add(saveChangesButton, gbc);
		}
	}

	private int[] csvToIntArray(String csv) {
		String[] separated = csv.split(",", -1);
		int[] validInts = new int[separated.length];
		for (int i = 0; i < validInts.length; i++) {
			try {
				validInts[i] = Integer.parseInt(separated[i].trim());
			} catch (Exception e) {
				return new int[0];
			}
		}
		return validInts;
	}

	private float parseOrDefault(Object o, float defaultResult) {
		float result = defaultResult;
		String s = "";
		if (o instanceof JTextField) {
			s = ((JTextField) o).getText();
		}
		if (o instanceof String) {
			s = (String) o;
		}
		try {
			result = Float.parseFloat(s);
		} catch (Exception ignored) {
		}

		return result;
	}

	private Integer parseIntOrNull(JTextField field) {
		if (fieldEmpty(field)) {
			return null;
		}

		try {
			return Integer.parseInt(field.getText().trim());
		} catch (Exception ignored) {
			return null;
		}
	}

	private int[] shortArrayToUnsignedIntArray(short[] values) {
		if (values == null || values.length == 0) {
			return new int[0];
		}

		int[] result = new int[values.length];
		for (int i = 0; i < values.length; i++) {
			result[i] = values[i] & 0xFFFF;
		}

		return result;
	}

	private AnimationID getAnimationIdFromRaw(Integer rawAnimationId) {
		if (rawAnimationId == null) {
			return null;
		}

		for (AnimationID value : AnimationID.values()) {
			if (value.getId() != null && value.getId().equals(rawAnimationId)) {
				return value;
			}
		}

		return null;
	}

	private int[] findSceneNpcIdleWalkAnimations(int npcId) {
		WorldView worldView = plugin.client.getTopLevelWorldView();
		if (worldView == null) {
			return null;
		}

		Player localPlayer = plugin.client.getLocalPlayer();
		WorldPoint playerPoint = localPlayer == null ? null : localPlayer.getWorldLocation();

		NPC closestMatch = null;
		int bestDistance = Integer.MAX_VALUE;

		for (NPC sceneNpc : worldView.npcs()) {
			if (sceneNpc == null) {
				continue;
			}

			int sceneNpcId = sceneNpc.getId();
			NPCComposition transformed = sceneNpc.getTransformedComposition();
			if (transformed != null) {
				sceneNpcId = transformed.getId();
			}

			if (sceneNpcId != npcId) {
				continue;
			}

			int distance = 0;
			if (playerPoint != null && sceneNpc.getWorldLocation() != null) {
				distance = sceneNpc.getWorldLocation().distanceTo2D(playerPoint);
			}

			if (closestMatch == null || distance < bestDistance) {
				closestMatch = sceneNpc;
				bestDistance = distance;
			}
		}

		if (closestMatch == null) {
			return null;
		}

		return new int[]{closestMatch.getIdlePoseAnimation(), closestMatch.getWalkAnimation()};
	}

	private void importNpcDefinitionFromField() {
		Integer npcId = parseIntOrNull(npcIdImportField);
		if (npcId == null) {
			return;
		}

		plugin.clientThread.invokeLater(() -> {
			NPCComposition npc = plugin.client.getNpcDefinition(npcId);
			if (npc == null) {
				return;
			}

			if (npc.getConfigs() != null) {
				NPCComposition transformed = npc.transform();
				if (transformed != null) {
					npc = transformed;
				}
			}

			final int[] models = npc.getModels() == null ? new int[0] : npc.getModels();
			final int[] recolorFind = shortArrayToUnsignedIntArray(npc.getColorToReplace());
			final int[] recolorReplace = shortArrayToUnsignedIntArray(npc.getColorToReplaceWith());
			final String npcName = npc.getName();
			final int[] idleWalkAnimations = findSceneNpcIdleWalkAnimations(npc.getId());

			SwingUtilities.invokeLater(() -> {
				modelIdsField.setText(Util.intArrayToString(models));
				recolorFindField.setText(Util.intArrayToString(recolorFind));
				recolorReplaceField.setText(Util.intArrayToString(recolorReplace));

				if (idleWalkAnimations != null) {
					manualFieldsToggle.setSelected(true);
					manualAnimIdIdleSelect.setText(String.valueOf(idleWalkAnimations[0]));
					manualAnimIdMoveSelect.setText(String.valueOf(idleWalkAnimations[1]));

					AnimationID importedIdle = getAnimationIdFromRaw(idleWalkAnimations[0]);
					if (importedIdle != null) {
						animIdIdleSelect.setSelectedItem(importedIdle);
					}

					AnimationID importedMove = getAnimationIdFromRaw(idleWalkAnimations[1]);
					if (importedMove != null) {
						animIdMoveSelect.setSelectedItem(importedMove);
					}
				}

				if (npcName != null && !npcName.trim().isEmpty() && fieldEmpty(entityNameField)) {
					entityNameField.setText(npcName);
				}

				update();
			});
		});
	}

	private CitizenInfo buildCitizenInfo(int regionId, WorldPoint location) {
		CitizenInfo info = new CitizenInfo();
		info.uuid = UUID.randomUUID();
		info.regionId = regionId;
		info.name = entityNameField.getText();
		info.examineText = examineTextField.getText();
		info.worldLocation = location;
		info.entityType = (EntityType) entityTypeSelection.getSelectedItem();

		if (manualFieldsToggle.isSelected()) {
			info.idleAnimationRawId = parseIntOrNull(manualAnimIdIdleSelect);
			info.idleAnimation = getAnimationIdFromRaw(info.idleAnimationRawId);

			info.moveAnimationRawId = parseIntOrNull(manualAnimIdMoveSelect);
			info.moveAnimation = getAnimationIdFromRaw(info.moveAnimationRawId);
		} else {
			info.idleAnimation = (AnimationID) animIdIdleSelect.getSelectedItem();
			info.moveAnimation = (AnimationID) animIdMoveSelect.getSelectedItem();
			info.idleAnimationRawId = null;
			info.moveAnimationRawId = null;
		}

		info.modelIds = csvToIntArray(modelIdsField.getText());
		info.modelRecolorFind = csvToIntArray(recolorFindField.getText());
		info.modelRecolorReplace = csvToIntArray(recolorReplaceField.getText());
		info.heightOffset = parseIntOrNull(heightOffsetField);
		info.baseOrientation = ((CardinalDirection) orientationField.getSelectedItem()).getAngle();
		info.remarks = remarksField.getText().length() > 0 ? remarksField.getText().split(",", -1) : new String[]{};

		if (fieldEmpty(scaleFieldX) && fieldEmpty(scaleFieldY) && fieldEmpty(scaleFieldZ)) {
			info.scale = null;
		} else {
			info.scale = new float[]{
				parseOrDefault(scaleFieldX, 1),
				parseOrDefault(scaleFieldY, 1),
				parseOrDefault(scaleFieldZ, 1),
			};
		}
		if (fieldEmpty(translateFieldX) && fieldEmpty(translateFieldY) && fieldEmpty(translateFieldZ)) {
			info.translate = null;
		} else {
			info.translate = new float[]{
				parseOrDefault(translateFieldX, 0),
				parseOrDefault(translateFieldY, 0),
				parseOrDefault(translateFieldZ, 0),
			};
		}

		if (info.entityType == EntityType.WanderingCitizen) {
			info.wanderBoxTR = wanderRegionTR;
			info.wanderBoxBL = wanderRegionBL;
		}

		return info;
	}

	private SceneryInfo buildSceneryInfo(WorldPoint location) {
		SceneryInfo info = new SceneryInfo();
		info.uuid = UUID.randomUUID();
		info.regionId = location.getRegionID();
		info.entityType = EntityType.Scenery;
		info.worldLocation = location;
		info.modelIds = csvToIntArray(modelIdsField.getText());
		info.modelRecolorFind = csvToIntArray(recolorFindField.getText());
		info.modelRecolorReplace = csvToIntArray(recolorReplaceField.getText());
		info.heightOffset = parseIntOrNull(heightOffsetField);
		info.baseOrientation = ((CardinalDirection) orientationField.getSelectedItem()).getAngle();

		if (manualFieldsToggle.isSelected()) {
			info.idleAnimationRawId = parseIntOrNull(manualAnimIdIdleSelect);
			info.idleAnimation = getAnimationIdFromRaw(info.idleAnimationRawId);
		} else {
			info.idleAnimation = (AnimationID) animIdIdleSelect.getSelectedItem();
			info.idleAnimationRawId = null;
		}

		if (fieldEmpty(scaleFieldX) && fieldEmpty(scaleFieldY) && fieldEmpty(scaleFieldZ)) {
			info.scale = null;
		} else {
			info.scale = new float[]{
				parseOrDefault(scaleFieldX, 1),
				parseOrDefault(scaleFieldY, 1),
				parseOrDefault(scaleFieldZ, 1),
			};
		}

		if (fieldEmpty(translateFieldX) && fieldEmpty(translateFieldY) && fieldEmpty(translateFieldZ)) {
			info.translate = null;
		} else {
			info.translate = new float[]{
				parseOrDefault(translateFieldX, 0),
				parseOrDefault(translateFieldY, 0),
				parseOrDefault(translateFieldZ, 0),
			};
		}
		return info;
	}

	private <T extends JComponent> T createLabeledComponent(T component, String label, JPanel panel, GridBagConstraints constraints) {
		final JPanel container = new JPanel();
		container.setLayout(new BorderLayout());

		final JLabel uiLabel = new JLabel(label);

		uiLabel.setFont(FontManager.getRunescapeSmallFont());
		uiLabel.setBorder(new EmptyBorder(0, 0, 0, 0));
		uiLabel.setForeground(Color.WHITE);

		container.add(uiLabel, BorderLayout.NORTH);
		container.add(component, BorderLayout.CENTER);

		panel.add(container, constraints);
		allElements.add(container);
		allElements.add(component);
		return component;
	}

	//Creates multiple components under a single label
	private void createLabeledMultiComponent(String label, JPanel panel, GridBagConstraints constraints, JComponent... comps) {
		final JPanel container = new JPanel();
		container.setLayout(new GridBagLayout());

		GridBagConstraints containerGbc = new GridBagConstraints();
		containerGbc.fill = GridBagConstraints.HORIZONTAL;
		containerGbc.insets = new Insets(0, 0, 0, 2);
		containerGbc.weightx = 0.5;
		containerGbc.gridwidth = comps.length;
		final JLabel uiLabel = new JLabel(label);

		uiLabel.setFont(FontManager.getRunescapeSmallFont());
		uiLabel.setBorder(new EmptyBorder(0, 0, 0, 0));
		uiLabel.setForeground(Color.WHITE);

		containerGbc.gridx = 0;
		container.add(uiLabel, containerGbc);
		containerGbc.weightx = 0.5f;
		containerGbc.gridwidth = 1;
		int i = 0;
		for (JComponent comp : comps) {
			containerGbc.gridx = i++;
			container.add(comp, containerGbc);
			allElements.add(comp);
		}
		panel.add(container, constraints);
	}

	private void entityTypeChanged() {
		for (JComponent jc : allElements) {
			jc.setVisible(true);
			jc.getParent().setVisible(true);
		}

		EntityType type = (EntityType) entityTypeSelection.getSelectedItem();

		boolean checked = manualFieldsToggle.isSelected();
		// Turn off irrelevant components
		switch (type) {
			// We get the parents because they are each in individual containers with their labels
			case StationaryCitizen:
			case ScriptedCitizen:
				selectWanderTR.getParent().setVisible(false);
				selectWanderBL.getParent().setVisible(false);

				animIdIdleSelect.getParent().setVisible(!checked);
				animIdMoveSelect.getParent().setVisible(!checked);
				manualAnimIdIdleSelect.getParent().setVisible(checked);
				manualAnimIdMoveSelect.getParent().setVisible(checked);
				break;

			case WanderingCitizen:
				animIdIdleSelect.getParent().setVisible(!checked);
				animIdMoveSelect.getParent().setVisible(!checked);
				manualAnimIdIdleSelect.getParent().setVisible(checked);
				manualAnimIdMoveSelect.getParent().setVisible(checked);
				break;

			case Scenery:
				entityNameField.getParent().setVisible(false);
				examineTextField.getParent().setVisible(false);
				manualAnimIdMoveSelect.getParent().setVisible(false);
				animIdIdleSelect.getParent().setVisible(!checked);
				manualAnimIdIdleSelect.getParent().setVisible(checked);
				remarksField.getParent().setVisible(false);
				selectWanderTR.getParent().setVisible(false);
				selectWanderBL.getParent().setVisible(false);
				break;
		}

		if (type == EntityType.StationaryCitizen || type == EntityType.Scenery) {
			animIdMoveSelect.getParent().setVisible(false);
		}
	}

	public void setSelectedEntity(Entity<?> e) {
		if (e == null) {
			selectedEntity = null;
			clearEditorFields();
			update();
			return;
		}

		if (selectedEntity == e) {
			selectedEntity = null;
			clearEditorFields();
			update();
			return;
		}

		selectedEntity = e;
		selectedPosition = e.getWorldLocation();
		clearEditorFields();

		entityTypeSelection.setSelectedItem(e.entityType);
		orientationField.setSelectedItem(CardinalDirection.fromInteger(e.baseOrientation));

		AnimationID idleAnimationFromRaw = e.idleAnimationRawId == null ? null : getAnimationIdFromRaw(e.idleAnimationRawId);
		animIdIdleSelect.setSelectedItem(idleAnimationFromRaw != null ? idleAnimationFromRaw : e.idleAnimationId);
		Integer effectiveIdleAnimationId = e.idleAnimationRawId != null
			? e.idleAnimationRawId
			: (e.idleAnimationId == null ? null : e.idleAnimationId.getId());
		manualAnimIdIdleSelect.setText(effectiveIdleAnimationId == null ? "" : String.valueOf(effectiveIdleAnimationId));

		modelIdsField.setText(e.getModelIDsString());
		recolorFindField.setText(e.getRecolorFindString());
		recolorReplaceField.setText(e.getRecolorReplaceString());

		if (e.translate != null) {
			translateFieldX.setText(String.valueOf(e.translate[0]));
			translateFieldY.setText(String.valueOf(e.translate[1]));
			translateFieldZ.setText(String.valueOf(e.translate[2]));
		}

		if (e.scale != null) {
			scaleFieldX.setText(String.valueOf(e.scale[0]));
			scaleFieldY.setText(String.valueOf(e.scale[1]));
			scaleFieldZ.setText(String.valueOf(e.scale[2]));
		}

		heightOffsetField.setText(e.heightOffset == null ? "" : String.valueOf(e.heightOffset));

		if (e.isCitizen()) {
			Citizen<?> c = (Citizen<?>) e;
			entityNameField.setText(c.name);
			examineTextField.setText(c.examine);

			AnimationID moveAnimationFromRaw = c.movingAnimationRawId == null ? null : getAnimationIdFromRaw(c.movingAnimationRawId);
			animIdMoveSelect.setSelectedItem(moveAnimationFromRaw != null ? moveAnimationFromRaw : c.movingAnimationId);
			AnimationID movingAnimationId = c.movingAnimationId;
			Integer effectiveMoveAnimationId = c.movingAnimationRawId != null
				? c.movingAnimationRawId
				: (movingAnimationId == null ? null : movingAnimationId.getId());
			manualAnimIdMoveSelect.setText(effectiveMoveAnimationId == null ? "" : String.valueOf(effectiveMoveAnimationId));

			if (c.remarks != null) {
				remarksField.setText(String.join(",", c.remarks));
			}
		}

		if (e.entityType == EntityType.WanderingCitizen) {
			WanderingCitizen w = (WanderingCitizen) e;
			wanderRegionBL = w.wanderRegionBL;
			wanderRegionTR = w.wanderRegionTR;
		}

		update();
	}

	public void cleanup() {
		selectedPosition = null;
		selectedEntity = null;
	}

	private void clearEditorFields() {
		entityNameField.setText("");
		examineTextField.setText("A Citizen of Gielinor");
		remarksField.setText("");
		modelIdsField.setText("");
		recolorFindField.setText("");
		recolorReplaceField.setText("");
		scaleFieldX.setText("");
		scaleFieldY.setText("");
		scaleFieldZ.setText("");
		translateFieldX.setText("");
		translateFieldY.setText("");
		translateFieldZ.setText("");
		heightOffsetField.setText("");
		manualAnimIdIdleSelect.setText("");
		manualAnimIdMoveSelect.setText("");
		animIdIdleSelect.setSelectedItem(AnimationID.HumanIdle);
		animIdMoveSelect.setSelectedItem(AnimationID.HumanWalk);
		orientationField.setSelectedItem(CardinalDirection.South);
		wanderRegionBL = null;
		wanderRegionTR = null;
	}
}
