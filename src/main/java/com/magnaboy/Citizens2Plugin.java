package com.magnaboy;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Menu;
import net.runelite.api.ModelData;
import net.runelite.api.Point;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.geometry.SimplePolygon;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(name = "Citizens 2", description = "Adds citizens to help bring life to the world")
public class Citizens2Plugin extends Plugin {
	public static HashMap<Integer, CitizenRegion> activeRegions = new HashMap<>();
	public static boolean shuttingDown;
	@Inject
	public Client client;
	@Inject
	public ClientThread clientThread;
	public CitizenPanel panel;

	public boolean IS_DEVELOPMENT = Boolean.getBoolean("citizens2.development")
		|| Boolean.getBoolean("citizens2.dev")
		|| "true".equalsIgnoreCase(System.getenv("CITIZENS2_DEVELOPMENT"))
		|| Boolean.getBoolean("citizens.development")
		|| Boolean.getBoolean("citizens.dev")
		|| "true".equalsIgnoreCase(System.getenv("CITIZENS_DEVELOPMENT"));
	public boolean entitiesAreReady = false;
	@Inject
	public Gson gson;
	@Inject
	ChatMessageManager chatMessageManager;
	@Inject
	@Getter
	private CitizensConfig config;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private CitizensOverlay citizensOverlay;
	@Inject
	private ClientToolbar clientToolbar;
	private NavigationButton navButton;
	private final List<RuneLiteObject> debugModelPartObjects = new ArrayList<>();
	private long debugSnapshotTickCounter = 0;
	private long debugTraceTickCounter = 0;
	private final HashMap<UUID, String> debugLastEntityTraceState = new HashMap<>();
	private String debugLastSelectedEntitySignature = "";
	private String debugLastSelectedTileSignature = "";

	@Provides
	CitizensConfig getConfig(ConfigManager configManager) {
		return configManager.getConfig(CitizensConfig.class);
	}

	public boolean isReady() {
		return entitiesAreReady && client.getLocalPlayer() != null;
	}

	@Override
	protected void startUp() {
		debugSnapshotTickCounter = 0;
		debugTraceTickCounter = 0;
		debugLastEntityTraceState.clear();
		debugLastSelectedEntitySignature = "";
		debugLastSelectedTileSignature = "";
		log.info("Citizens 2 starting up (gameState={}, devMode={})", client.getGameState(), IS_DEVELOPMENT);
		log.info("Citizens 2 offsets: baseHeightOffset={}, debugAirlift={}, debugAirliftOffset={}",
			config.citizenHeightOffset(),
			config.debugAirliftCitizens(),
			config.debugAirliftOffset());
		CitizenRegion.init(this);

		panel = injector.getInstance(CitizenPanel.class);
		panel.init(this);
		overlayManager.add(citizensOverlay);

		// For now, the only thing in the panel is dev stuff
		if (IS_DEVELOPMENT) {
			// Add to sidebar
			final BufferedImage icon = ImageUtil.loadImageResource(Citizens2Plugin.class, "/citizens_icon.png");
			navButton = NavigationButton.builder()
				.tooltip("Citizens 2")
				.icon(icon)
				.priority(7)
				.panel(panel)
				.build();
			clientToolbar.addNavigation(navButton);
		}

		if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null) {
			checkRegions();
			CitizenRegion.updateAllEntities();
			logAdvancedSnapshot("startup-logged-in");
			logEntityTransitions("startup-logged-in");
		} else {
			log.info("Citizens 2 waiting for LOGGED_IN before region load");
		}
		Util.initAnimationData(this);
	}

	@Override
	protected void shutDown() {
		cleanupAll();
		if (navButton != null) {
			clientToolbar.removeNavigation(navButton);
		}
	}

	void reload() {
		shutDown();
		startUp();
	}

	protected void despawnAll() {
		CitizenRegion.forEachActiveEntity(Entity::despawn);
	}

	public void debugSpawnModelParts(WorldPoint baseWorldPoint, int[] modelIds) {
		debugSpawnModelParts(baseWorldPoint, modelIds, null, null);
	}

	public void debugSpawnModelParts(WorldPoint baseWorldPoint, int[] modelIds, int[] recolorFind, int[] recolorReplace) {
		clientThread.invokeLater(() -> {
			clearDebugModelPartsInternal();

			if (baseWorldPoint == null) {
				log.warn("Model ID debug requested without a selected world location");
				return;
			}

			if (modelIds == null || modelIds.length == 0) {
				log.warn("Model ID debug requested with no model IDs");
				return;
			}

			int[] recolorFindSafe = recolorFind == null ? new int[0] : recolorFind;
			int[] recolorReplaceSafe = recolorReplace == null ? new int[0] : recolorReplace;
			boolean applyRecolors = recolorFindSafe.length > 0 && recolorFindSafe.length == recolorReplaceSafe.length;
			if (!applyRecolors && (recolorFindSafe.length > 0 || recolorReplaceSafe.length > 0)) {
				log.warn("Model ID debug recolor mismatch: find={}, replace={}", recolorFindSafe.length, recolorReplaceSafe.length);
			}

			final int columns = 4;
			int spawned = 0;

			for (int i = 0; i < modelIds.length; i++) {
				int modelId = modelIds[i];
				ModelData data = client.loadModelData(modelId);
				if (data == null) {
					log.warn("Model ID debug missing model data for id {}", modelId);
					continue;
				}

				ModelData finalData = client.mergeModels(new ModelData[]{data}, 1);
				if (finalData == null) {
					log.warn("Model ID debug failed to clone model data for id {}", modelId);
					continue;
				}

				if (applyRecolors) {
					for (int c = 0; c < recolorFindSafe.length; c++) {
						finalData.recolor((short) recolorFindSafe[c], (short) recolorReplaceSafe[c]);
					}
				}

				int dx = i % columns;
				int dy = i / columns;
				WorldPoint modelPoint = new WorldPoint(baseWorldPoint.getX() + dx, baseWorldPoint.getY() + dy, baseWorldPoint.getPlane());
				WorldView worldView = client.getTopLevelWorldView();
				if (worldView == null) {
					log.warn("Model ID debug world view unavailable");
					return;
				}
				LocalPoint localPoint = LocalPoint.fromWorld(worldView, modelPoint);
				if (localPoint == null) {
					log.warn("Model ID debug could not place model id {} at {}", modelId, modelPoint);
					continue;
				}

				RuneLiteObject object = client.createRuneLiteObject();
				object.setModel(finalData.light(64, 850, -30, -50, -30));
				object.setLocation(localPoint, modelPoint.getPlane());
				object.setActive(true);
				debugModelPartObjects.add(object);
				spawned++;

				log.info("Model ID debug part {} => id {} at {}", i, modelId, modelPoint);
			}

			log.info("Model ID debug spawned {} / {} model parts near {}", spawned, modelIds.length, baseWorldPoint);
		});
	}

	public void clearDebugModelParts() {
		clientThread.invokeLater(this::clearDebugModelPartsInternal);
	}

	private void clearDebugModelPartsInternal() {
		for (RuneLiteObject object : debugModelPartObjects) {
			if (object != null && object.isActive()) {
				object.setActive(false);
			}
		}
		debugModelPartObjects.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		GameState newState = gameStateChanged.getGameState();
		log.debug("Citizens 2 observed game state change to {}", newState);

		if (newState == GameState.LOGGED_IN) {
			checkRegions();
			CitizenRegion.updateAllEntities();
		}

		if (newState == GameState.LOADING) {
			despawnAll();
			CitizenRegion.updateAllEntities();
		}
	}

	@Schedule(
		period = 3,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)
	public void citizenBehaviourTick() {
		clientThread.invokeLater(() -> {
			if (!isReady()) {
				return;
			}

			for (CitizenRegion r : activeRegions.values()) {
				r.runOncePerTimePeriod(10, 3, entity -> {
					if (entity instanceof WanderingCitizen && entity.isActive()) {
						((WanderingCitizen) entity).wander();
					}
				});

				r.runOncePerTimePeriod(60, 3, entity -> {
					if (entity.isActive() && entity.isCitizen() && entity.distanceToPlayer() < 15) {
						((Citizen<?>) entity).sayRandomRemark();
					}
				});
			}

			panel.update();
		});
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		CitizenRegion.updateAllEntities();

		if (isVerboseDebugLoggingEnabled()) {
			debugSnapshotTickCounter++;
			int interval = Math.max(1, config.debugVerboseLogIntervalTicks());
			if (debugSnapshotTickCounter % interval == 0) {
				logAdvancedSnapshot("tick-" + debugSnapshotTickCounter);
			}
		}

		if (isDeepTraceLoggingEnabled()) {
			debugTraceTickCounter++;
			int traceInterval = Math.max(1, config.debugTraceIntervalTicks());
			if (debugTraceTickCounter % traceInterval == 0) {
				logEntityTransitions("tick-" + debugTraceTickCounter);
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (!"citizens2".equals(event.getGroup()) && !"citizens".equals(event.getGroup())) {
			return;
		}

		String key = event.getKey();
		boolean offsetChanged = "citizenHeightOffset".equals(key)
			|| "debugAirliftCitizens".equals(key)
			|| "debugAirliftOffset".equals(key);
		boolean verboseChanged = "debugVerboseLogging".equals(key)
			|| "debugVerboseLogIntervalTicks".equals(key)
			|| "debugEntityFilter".equals(key);
		boolean traceChanged = "debugTraceEntityTransitions".equals(key)
			|| "debugTraceIntervalTicks".equals(key)
			|| "debugTraceMaxEventsPerInterval".equals(key)
			|| "debugTraceIncludeMovement".equals(key)
			|| "debugEntityFilter".equals(key);

		if (!offsetChanged && !verboseChanged && !traceChanged) {
			return;
		}

		clientThread.invokeLater(() -> {
			if (offsetChanged) {
				despawnAll();
				CitizenRegion.updateAllEntities();
				log.info("Citizens 2 offsets applied: baseHeightOffset={}, debugAirlift={}, debugAirliftOffset={}",
					config.citizenHeightOffset(),
					config.debugAirliftCitizens(),
					config.debugAirliftOffset());
			}

			if (verboseChanged) {
				log.info("Citizens 2 verbose logging updated: enabled={}, intervalTicks={}, filter='{}'",
					config.debugVerboseLogging(),
					config.debugVerboseLogIntervalTicks(),
					config.debugEntityFilter());
			}

			if (traceChanged) {
				log.info("Citizens 2 deep trace updated: enabled={}, intervalTicks={}, maxEvents={}, includeMovement={}, filter='{}'",
					config.debugTraceEntityTransitions(),
					config.debugTraceIntervalTicks(),
					config.debugTraceMaxEventsPerInterval(),
					config.debugTraceIncludeMovement(),
					config.debugEntityFilter());
			}

			if (isVerboseDebugLoggingEnabled()) {
				logAdvancedSnapshot("config-" + key);
			}

			if (isDeepTraceLoggingEnabled()) {
				logEntityTransitions("config-" + key);
			} else {
				debugLastEntityTraceState.clear();
				debugLastSelectedEntitySignature = "";
				debugLastSelectedTileSignature = "";
			}
		});
	}

	private boolean isVerboseDebugLoggingEnabled() {
		return IS_DEVELOPMENT && config != null && config.debugVerboseLogging();
	}

	private boolean isDeepTraceLoggingEnabled() {
		return IS_DEVELOPMENT && config != null && config.debugTraceEntityTransitions();
	}

	private static String worldPointSummary(WorldPoint worldPoint) {
		return worldPoint == null ? "none" : Util.worldPointToShortCoord(worldPoint);
	}

	private String buildEntityTraceSignature(Entity<?> entity, boolean includeMovement) {
		String location = includeMovement ? worldPointSummary(entity.getWorldLocation()) : "-";
		int animationId = entity.getAnimationID();
		String target = "-";
		if (entity.isCitizen()) {
			Citizen.Target citizenTarget = ((Citizen<?>) entity).getCurrentTarget();
			if (citizenTarget != null && citizenTarget.worldDestinationPosition != null) {
				target = worldPointSummary(citizenTarget.worldDestinationPosition);
			}
		}

		return "active=" + entity.isActive()
			+ "|anim=" + animationId
			+ "|loc=" + location
			+ "|target=" + target;
	}

	private void logEntityTransitions(String reason) {
		if (!isDeepTraceLoggingEnabled()) {
			return;
		}

		String normalizedFilter = normalizeFilter(config.debugEntityFilter());
		boolean includeMovement = config.debugTraceIncludeMovement();
		int maxEvents = Math.max(1, config.debugTraceMaxEventsPerInterval());
		HashMap<UUID, String> nextState = new HashMap<>();

		int considered = 0;
		int changed = 0;
		int removed = 0;
		int emitted = 0;

		for (CitizenRegion region : activeRegions.values()) {
			if (region == null || region.entities == null) {
				continue;
			}

			for (Entity<?> entity : region.entities.values()) {
				if (entity == null || entity.uuid == null) {
					continue;
				}

				if (!matchesEntityFilter(entity, normalizedFilter)) {
					continue;
				}

				considered++;
				String currentSignature = buildEntityTraceSignature(entity, includeMovement);
				nextState.put(entity.uuid, currentSignature);

				String previousSignature = debugLastEntityTraceState.get(entity.uuid);
				if (previousSignature == null) {
					changed++;
					if (emitted < maxEvents) {
						log.info("Citizens 2 trace [{}] NEW {} :: {}", reason, entity.debugName(), currentSignature);
						emitted++;
					}
				} else if (!previousSignature.equals(currentSignature)) {
					changed++;
					if (emitted < maxEvents) {
						log.info("Citizens 2 trace [{}] CHG {} :: {} => {}", reason, entity.debugName(), previousSignature, currentSignature);
						emitted++;
					}
				}
			}
		}

		for (UUID priorId : debugLastEntityTraceState.keySet()) {
			if (!nextState.containsKey(priorId)) {
				removed++;
				if (emitted < maxEvents) {
					log.info("Citizens 2 trace [{}] REMOVED uuid={} :: {}", reason, priorId, debugLastEntityTraceState.get(priorId));
					emitted++;
				}
			}
		}

		Entity<?> selectedEntity = CitizenPanel.selectedEntity;
		String selectedEntitySignature = selectedEntity == null
			? "none"
			: selectedEntity.debugName() + "|" + selectedEntity.uuid + "|" + worldPointSummary(selectedEntity.getWorldLocation());
		boolean selectedEntityChanged = !selectedEntitySignature.equals(debugLastSelectedEntitySignature);
		if (selectedEntityChanged) {
			if (emitted < maxEvents) {
				log.info("Citizens 2 trace [{}] SELECTED_ENTITY {} => {}", reason, debugLastSelectedEntitySignature, selectedEntitySignature);
				emitted++;
			}
		}

		String selectedTileSignature = worldPointSummary(CitizenPanel.selectedPosition);
		boolean selectedTileChanged = !selectedTileSignature.equals(debugLastSelectedTileSignature);
		if (selectedTileChanged) {
			if (emitted < maxEvents) {
				log.info("Citizens 2 trace [{}] SELECTED_TILE {} => {}", reason, debugLastSelectedTileSignature, selectedTileSignature);
				emitted++;
			}
		}

		int significantEvents = changed + removed;
		if (significantEvents > 0 || selectedEntityChanged || selectedTileChanged) {
			int potentialEvents = significantEvents + (selectedEntityChanged ? 1 : 0) + (selectedTileChanged ? 1 : 0);
			int suppressed = Math.max(0, potentialEvents - emitted);
			log.info("Citizens 2 trace summary [{}]: considered={}, changed={}, removed={}, emitted={}, suppressed={}, filter='{}', includeMovement={}",
				reason,
				considered,
				changed,
				removed,
				emitted,
				suppressed,
				normalizedFilter,
				includeMovement);
		}

		debugLastEntityTraceState.clear();
		debugLastEntityTraceState.putAll(nextState);
		debugLastSelectedEntitySignature = selectedEntitySignature;
		debugLastSelectedTileSignature = selectedTileSignature;
	}

	private static String normalizeFilter(String filter) {
		if (filter == null) {
			return "";
		}
		return filter.trim().toLowerCase(Locale.ROOT);
	}

	private static boolean matchesEntityFilter(Entity<?> entity, String normalizedFilter) {
		if (normalizedFilter == null || normalizedFilter.isEmpty()) {
			return true;
		}

		if (entity == null) {
			return false;
		}

		if (entity.name != null && entity.name.toLowerCase(Locale.ROOT).contains(normalizedFilter)) {
			return true;
		}

		return entity.uuid != null && entity.uuid.toString().toLowerCase(Locale.ROOT).contains(normalizedFilter);
	}

	private void logAdvancedSnapshot(String reason) {
		if (!isVerboseDebugLoggingEnabled()) {
			return;
		}

		String normalizedFilter = normalizeFilter(config.debugEntityFilter());
		int totalEntities = 0;
		int activeEntities = 0;
		int citizenCount = 0;
		int sceneryCount = 0;
		int stationaryCount = 0;
		int wanderingCount = 0;
		int scriptedCount = 0;
		List<String> filterMatches = new ArrayList<>();

		for (CitizenRegion region : activeRegions.values()) {
			if (region == null || region.entities == null) {
				continue;
			}

			for (Entity<?> entity : region.entities.values()) {
				if (entity == null) {
					continue;
				}

				totalEntities++;
				if (entity.isActive()) {
					activeEntities++;
				}

				if (entity.isCitizen()) {
					citizenCount++;
					if (entity.entityType == EntityType.StationaryCitizen) {
						stationaryCount++;
					} else if (entity.entityType == EntityType.WanderingCitizen) {
						wanderingCount++;
					} else if (entity.entityType == EntityType.ScriptedCitizen) {
						scriptedCount++;
					}
				} else {
					sceneryCount++;
				}

				if (!normalizedFilter.isEmpty() && filterMatches.size() < 8 && matchesEntityFilter(entity, normalizedFilter)) {
					filterMatches.add(entity.debugName());
				}
			}
		}

		String selectedSummary = "none";
		Entity<?> selected = CitizenPanel.selectedEntity;
		if (selected != null) {
			String selectedLoc = selected.getWorldLocation() == null ? "unknown" : Util.worldPointToShortCoord(selected.getWorldLocation());
			selectedSummary = selected.debugName() + " loc=" + selectedLoc + " active=" + selected.isActive();
			if (selected.isCitizen()) {
				Citizen.Target target = ((Citizen<?>) selected).getCurrentTarget();
				if (target != null && target.worldDestinationPosition != null) {
					selectedSummary += " target=" + Util.worldPointToShortCoord(target.worldDestinationPosition);
				}
			}
		}

		log.info("Citizens 2 debug snapshot [{}]: regions={}, entities(total/active)={}/{}, citizens={}, scenery={}, stationary={}, wandering={}, scripted={}, selected={}",
			reason,
			activeRegions.size(),
			totalEntities,
			activeEntities,
			citizenCount,
			sceneryCount,
			stationaryCount,
			wanderingCount,
			scriptedCount,
			selectedSummary);

		if (!normalizedFilter.isEmpty()) {
			log.info("Citizens 2 debug filter '{}' match sample (max 8): {}", normalizedFilter, filterMatches);
		}
	}

	@Subscribe
	public void onClientTick(ClientTick ignored) {
		CitizenRegion.forEachActiveEntity((entity) -> {
			if (entity.isCitizen()) {
				((Citizen<?>) entity).onClientTick();
			}
		});
	}

	@Subscribe
	public void onMenuOpened(MenuOpened ignored) {
		final int[] firstMenuIndex = {1};

		Point mousePos = client.getMouseCanvasPosition();
		Menu menu = client.getMenu();
		final AtomicBoolean[] clickedCitizen = {new AtomicBoolean(false)};
		CitizenRegion.forEachActiveEntity(entity -> {
			if (entity.entityType == EntityType.Scenery && !IS_DEVELOPMENT) {
				return;
			}
			if ((entity.name != null && entity.examine != null) || IS_DEVELOPMENT) {
				SimplePolygon clickbox;
				try {
					clickbox = entity.getClickbox();
				} catch (IllegalStateException err) {
					return;
				}
				if (clickbox == null) {
					return;
				}
				boolean doesClickBoxContainMousePos = clickbox.contains(mousePos.getX(), mousePos.getY());
				if (doesClickBoxContainMousePos) {
					if (doesClickBoxContainMousePos) {
						menu.createMenuEntry(firstMenuIndex[0])
							.setOption("Examine")
							.setTarget("<col=fffe00>" + entity.name + "</col>")
							.setType(MenuAction.RUNELITE)
							.setParam0(0)
							.setParam1(0)
							.setDeprioritized(true);
					}
				}

				// Select/Deselect
				if (IS_DEVELOPMENT && doesClickBoxContainMousePos) {
					String action = "Select";
					if (CitizenPanel.selectedEntity == entity) {
						action = "Deselect";
						clickedCitizen[0].set(true);
					}

					menu.createMenuEntry(firstMenuIndex[0]++)
						.setOption(ColorUtil.wrapWithColorTag("Citizen Editor", Color.cyan))
						.setTarget(action + " <col=fffe00>" + entity.name + "</col>")
						.setType(MenuAction.RUNELITE)
						.setDeprioritized(true)
						.onClick(e -> {
							panel.setSelectedEntity(entity);
							panel.update();
						});
				}
			}
		});

		if (IS_DEVELOPMENT) {
			final WorldView worldView = client.getTopLevelWorldView();
			if (worldView == null) {
				return;
			}
			// Tile Selection
			final Tile selectedSceneTile = worldView.getSelectedSceneTile();
			if (selectedSceneTile == null) {
				return;
			}
			final boolean same = CitizenPanel.selectedPosition != null && CitizenPanel.selectedPosition.equals(selectedSceneTile.getWorldLocation());
			final String action = same ? "Deselect" : "Select";
			menu.createMenuEntry(firstMenuIndex[0]++)
				.setOption(ColorUtil.wrapWithColorTag("Citizen Editor", Color.cyan))
				.setTarget(action + " <col=fffe00>Tile</col>")
				.setType(MenuAction.RUNELITE)
				.setDeprioritized(true)
				.onClick(e -> {
					if (same) {
						CitizenPanel.selectedPosition = null;
					} else {
						CitizenPanel.selectedPosition = selectedSceneTile.getWorldLocation();
					}
					panel.update();
				});
			// Entity Deselect (from anywhere)
			if (CitizenPanel.selectedEntity != null && !clickedCitizen[0].get()) {
				String name = "Scenery Object";
				if (CitizenPanel.selectedEntity instanceof Citizen) {
					name = CitizenPanel.selectedEntity.name;
				}
				menu.createMenuEntry(firstMenuIndex[0] - 1)
					.setOption(ColorUtil.wrapWithColorTag("Citizen Editor", Color.cyan))
					.setTarget("Deselect <col=fffe00>" + name + "</col>")
					.setType(MenuAction.RUNELITE)
					.setDeprioritized(true)
					.onClick(e -> {
						panel.setSelectedEntity(CitizenPanel.selectedEntity);
						panel.update();
					});
			}
		}
	}

	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked event) {
		if (!event.getMenuOption().equals("Examine")) {
			return;
		}
		CitizenRegion.forEachActiveEntity((entity) -> {
			if (event.getMenuTarget().equals("<col=fffe00>" + entity.name + "</col>")) {
				event.consume();
				String chatMessage = new ChatMessageBuilder()
					.append(ChatColorType.NORMAL)
					.append(entity.examine)
					.build();

				chatMessageManager.queue(QueuedMessage.builder().type(ChatMessageType.NPC_EXAMINE)
					.runeLiteFormattedMessage(chatMessage)
					.timestamp((int) (System.currentTimeMillis() / 1000)).build());
			}
		});
	}

	private void checkRegions() {
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null) {
			log.warn("Citizens 2 world view is null; cannot load citizen regions yet");
			return;
		}

		int[] mapRegions = worldView.getMapRegions();
		if (mapRegions == null) {
			log.warn("Citizens 2 map regions are null; cannot load citizen regions yet");
			return;
		}

		List<Integer> loaded = Arrays.stream(mapRegions).boxed().collect(Collectors.toList());
		int removed = 0;
		List<Integer> staleRegions = activeRegions.keySet().stream()
			.filter(regionId -> !loaded.contains(regionId))
			.collect(Collectors.toList());
		for (Integer staleRegion : staleRegions) {
			activeRegions.remove(staleRegion);
			removed++;
		}

		int newlyLoaded = 0;
		int newlyLoadedEntities = 0;

		// Check for newly loaded regions
		for (int i : loaded) {
			if (!activeRegions.containsKey(i)) {
				CitizenRegion region = CitizenRegion.loadRegion(i);
				if (region != null) {
					activeRegions.put(i, region);
					newlyLoaded++;
					newlyLoadedEntities += region.entities.size();
				}
			}
		}
		entitiesAreReady = true;
		log.info("Citizens 2 region scan complete: sceneRegions={}, activeRegions={}, newlyLoaded={}, entitiesLoaded={}",
			loaded.size(),
			activeRegions.size(),
			newlyLoaded,
			newlyLoadedEntities);

		if (removed > 0) {
			log.debug("Citizens 2 pruned {} inactive regions from active set", removed);
		}

		if (activeRegions.isEmpty()) {
			log.warn("Citizens 2 has no active regions for current location");
		}
	}

	private void cleanupAll() {
		shuttingDown = true;
		debugSnapshotTickCounter = 0;
		debugTraceTickCounter = 0;
		debugLastEntityTraceState.clear();
		debugLastSelectedEntitySignature = "";
		debugLastSelectedTileSignature = "";
		entitiesAreReady = false;
		clearDebugModelParts();
		activeRegions.clear();
		despawnAll();
		overlayManager.remove(citizensOverlay);
		CitizenRegion.cleanUp();
		if (IS_DEVELOPMENT) {
			panel.cleanup();
			panel.update();
		}
		shuttingDown = false;
	}
}
