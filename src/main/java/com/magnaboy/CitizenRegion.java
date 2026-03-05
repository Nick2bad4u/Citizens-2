package com.magnaboy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import static com.magnaboy.Util.getRandomItem;
import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.EntityInfo;
import com.magnaboy.serialization.SceneryInfo;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CitizenRegion {

	public static final HashMap<Integer, CitizenRegion> regionCache = new HashMap<>();
	private static final float VALID_REGION_VERSION = 0.8f;
	private static final HashMap<Integer, CitizenRegion> dirtyRegions = new HashMap<>();
	private static final String RELATIVE_REGIONDATA_DIRECTORY = "src/main/resources/RegionData";
	private static CitizensPlugin plugin;
	public transient HashMap<UUID, Entity> entities = new HashMap<>();

	public float version;
	public int regionId;
	public List<CitizenInfo> citizenRoster = new ArrayList<>();
	public List<SceneryInfo> sceneryRoster = new ArrayList<>();
	public transient ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

	public static void init(CitizensPlugin p) {
		plugin = p;
	}

	public static CitizenRegion loadRegion(int regionId) {
		return loadRegion(regionId, false);
	}

	public static CitizenRegion loadRegion(int regionId, Boolean createIfNotExists) {
		if (regionCache.containsKey(regionId)) {
			return regionCache.get(regionId);
		}

		InputStream inputStream = plugin.getClass().getClassLoader().getResourceAsStream("RegionData/" + regionId + ".json");
		if (inputStream == null) {
			// No region file was found.
			// If in development, create one, save it and then try to load it.
			if (plugin.IS_DEVELOPMENT && createIfNotExists) {
				CitizenRegion region = new CitizenRegion();
				region.regionId = regionId;
				region.version = VALID_REGION_VERSION;
				try {
					region.saveRegion();
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}
				return loadRegion(regionId, false);
			}
			return null;
		}

		try (Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
			CitizenRegion region = plugin.gson.fromJson(reader, CitizenRegion.class);
			if (region == null) {
				log.warn("Citizen region {} parsed as null", regionId);
				return null;
			}
			if (region.version != VALID_REGION_VERSION) {
				log.warn("Citizen region {} has incompatible version {} (expected {})", regionId, region.version, VALID_REGION_VERSION);
				return null;
			}
			for (CitizenInfo cInfo : region.citizenRoster) {
				Citizen citizen = loadCitizen(plugin, cInfo);
				if (citizen != null) {
					region.entities.put(citizen.uuid, citizen);
				}
			}
			for (SceneryInfo sInfo : region.sceneryRoster) {
				Scenery scenery = loadScenery(plugin, sInfo);
				region.entities.put(scenery.uuid, scenery);
			}
			if (plugin.IS_DEVELOPMENT) {
				region.entities.values().forEach(Entity::validate);
			}
			log.debug("Loaded citizen region {} (citizens={}, scenery={}, entities={})",
				regionId,
				region.citizenRoster.size(),
				region.sceneryRoster.size(),
				region.entities.size());
			regionCache.put(regionId, region);
			return region;
		} catch (Exception e) {
			log.error("Failed to load citizen region {}", regionId, e);
			return null;
		}
	}

	public static void initCitizenInfo(Citizen citizen, CitizenInfo info) {
		// Citizen
		citizen.setName(info.name)
			.setExamine(info.examineText)
			.setRemarks(info.remarks);

		// Entity
		citizen.setModelIDs(info.modelIds)
			.setObjectToRemove(info.removedObject)
			.setModelRecolors(info.modelRecolorFind, info.modelRecolorReplace)
			.setIdleAnimation(info.idleAnimation)
			.setIdleAnimationRawId(info.idleAnimationRawId)
			.setHeightOffset(info.heightOffset)
			.setScale(info.scale)
			.setTranslate(info.translate)
			.setBaseOrientation(info.baseOrientation)
			.setUUID(info.uuid)
			.setWorldLocation(info.worldLocation)
			.setRegion(info.regionId);
	}

	public static Citizen loadCitizen(CitizensPlugin plugin, CitizenInfo info) {
		Citizen citizen;

		switch (info.entityType) {
			case WanderingCitizen:
				citizen = loadWanderingCitizen(plugin, info);
				break;
			case ScriptedCitizen:
				citizen = loadScriptedCitizen(plugin, info);
				break;
			default:
				citizen = loadStationaryCitizen(plugin, info);
				break;
		}

		if (info.mergedObjects != null) {
			info.mergedObjects.forEach(citizen::addMergedObject);
		}

		if (info.moveAnimationRawId != null) {
			citizen.movingAnimationRawId = info.moveAnimationRawId;
		}

		if (info.moveAnimation != null) {
			citizen.movingAnimationId = info.moveAnimation;
		}

		return citizen;
	}

	private static StationaryCitizen loadStationaryCitizen(CitizensPlugin plugin, CitizenInfo info) {
		info.entityType = EntityType.StationaryCitizen;
		StationaryCitizen citizen = new StationaryCitizen(plugin);
		initCitizenInfo(citizen, info);
		citizen.setWorldLocation(info.worldLocation);
		return citizen;
	}

	private static WanderingCitizen loadWanderingCitizen(CitizensPlugin plugin, CitizenInfo info) {
		info.entityType = EntityType.WanderingCitizen;
		WanderingCitizen citizen = new WanderingCitizen(plugin);
		initCitizenInfo(citizen, info);
		citizen.setWanderRegionBL(info.wanderBoxBL)
			.setWanderRegionTR(info.wanderBoxTR)
			.setWorldLocation(info.worldLocation)
			.setBoundingBox(info.wanderBoxBL, info.wanderBoxTR)
			.setBaseOrientation(getRandomItem(new CardinalDirection[]{CardinalDirection.North, CardinalDirection.South, CardinalDirection.East, CardinalDirection.West}));
		return citizen;
	}

	private static ScriptedCitizen loadScriptedCitizen(CitizensPlugin plugin, CitizenInfo info) {
		info.entityType = EntityType.ScriptedCitizen;
		ScriptedCitizen citizen = new ScriptedCitizen(plugin);
		initCitizenInfo(citizen, info);
		citizen.setWorldLocation(info.worldLocation)
			.setScript(info.startScript);

		citizen.baseLocation = info.worldLocation;
		return citizen;
	}

	public static Scenery loadScenery(CitizensPlugin plugin, SceneryInfo info) {
		Scenery scenery = new Scenery(plugin).setModelIDs(info.modelIds)
			.setModelRecolors(info.modelRecolorFind, info.modelRecolorReplace)
			.setIdleAnimation(info.idleAnimation)
			.setIdleAnimationRawId(info.idleAnimationRawId)
			.setHeightOffset(info.heightOffset)
			.setScale(info.scale)
			.setTranslate(info.translate)
			.setBaseOrientation(info.baseOrientation)
			.setUUID(info.uuid)
			.setWorldLocation(info.worldLocation)
			.setRegion(info.regionId);

		if (info.mergedObjects != null) {
			info.mergedObjects.forEach(scenery::addMergedObject);
		}

		return scenery;
	}

	public static void forEachActiveEntity(Consumer<Entity> function) {
		for (CitizenRegion r : regionCache.values()) {
			if (r != null) {
				for (Entity e : r.entities.values()) {
					if (e != null && e.distanceToPlayer() <= Util.MAX_ENTITY_RENDER_DISTANCE) {
						function.accept(e);
					}
				}
			}
		}
	}

	public static void forEachEntity(Consumer<Entity> function) {
		regionCache.forEach((regionId, r) -> {
			if (r != null) {
				r.entities.forEach((id, e) -> {
					if (e != null) {
						function.accept(e);
					}
				});
			}
		});
	}

	public static void cleanUp() {
		forEachEntity(Entity::despawn);

		for (CitizenRegion r : regionCache.values()) {
			r.citizenRoster.clear();
			r.sceneryRoster.clear();
			r.entities.clear();
		}
		regionCache.clear();
		dirtyRegions.clear();
	}

	// DEVELOPMENT SECTION
	public static Citizen spawnCitizenFromPanel(CitizenInfo info) {
		Citizen citizen = loadCitizen(plugin, info);
		CitizenRegion region = loadRegion(info.regionId, true);
		region.entities.put(info.uuid, citizen);
		region.citizenRoster.add(info);
		dirtyRegion(region);
		updateAllEntities();
		return citizen;
	}

	public static Scenery spawnSceneryFromPanel(SceneryInfo info) {
		Scenery scenery = loadScenery(plugin, info);
		CitizenRegion region = loadRegion(info.regionId, true);
		region.entities.put(info.uuid, scenery);
		region.sceneryRoster.add(info);
		dirtyRegion(region);
		updateAllEntities();
		return scenery;
	}

	public static void updateEntity(EntityInfo info) {
		CitizenRegion region = regionCache.get(info.regionId);
		if (region == null) {
			return;
		}

		if (info.entityType == EntityType.Scenery) {
			Entity e = region.entities.get(info.uuid);
			if (e == null) {
				return;
			}
			Scenery updated = loadScenery(plugin, (SceneryInfo) info);

			removeEntityFromRegion(e);
			addEntityToRegion(updated, info);
		} else {
			Entity e = region.entities.get(info.uuid);
			if (e == null) {
				return;
			}
			Citizen updated = loadCitizen(plugin, (CitizenInfo) info);

			removeEntityFromRegion(e);
			addEntityToRegion(updated, info);
		}

		dirtyRegion(region);
	}

	public static Entity getEntity(int regionId, UUID uuid) {
		CitizenRegion region = regionCache.get(regionId);
		if (region == null) {
			return null;
		}

		return region.entities.get(uuid);
	}

	public static void dirtyRegion(CitizenRegion region) {
		dirtyRegions.put(region.regionId, region);
	}

	public static void clearDirtyRegions() {
		dirtyRegions.clear();
	}

	public static void addEntityToRegion(Entity e, EntityInfo info) {
		CitizenRegion region = regionCache.get(e.regionId);
		region.entities.put(e.uuid, e);
		if (info instanceof CitizenInfo) {
			region.citizenRoster.add((CitizenInfo) info);
		}
		if (info instanceof SceneryInfo) {
			region.sceneryRoster.add((SceneryInfo) info);
		}
	}

	private static void removeEntityFromRegion(Citizen citizen, CitizenRegion region) {
		CitizenInfo info = region.citizenRoster.stream()
			.filter(c -> Objects.equals(c.uuid, citizen.uuid))
			.findFirst()
			.orElse(null);
		region.citizenRoster.remove(info);
	}

	private static void removeEntityFromRegion(Scenery scenery, CitizenRegion region) {
		SceneryInfo info = region.sceneryRoster.stream()
			.filter(c -> Objects.equals(c.uuid, scenery.uuid))
			.findFirst()
			.orElse(null);
		region.sceneryRoster.remove(info);
	}

	public static void removeEntityFromRegion(Entity e) {
		CitizenRegion region = regionCache.get(e.regionId);
		if (e instanceof Citizen) {
			removeEntityFromRegion((Citizen) e, region);
		}
		if (e instanceof Scenery) {
			removeEntityFromRegion((Scenery) e, region);
		}

		region.entities.remove(e.uuid);
		e.despawn();
		dirtyRegion(region);
	}

	public static int dirtyRegionCount() {
		return dirtyRegions.size();
	}

	public static void saveDirtyRegions() {
		for (Map.Entry<Integer, CitizenRegion> region : dirtyRegions.entrySet()) {
			try {
				region.getValue().saveRegion();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		clearDirtyRegions();
	}

	public static void updateAllEntities() {
		for (CitizenRegion region : regionCache.values()) {
			region.updateEntities();
		}
		if (plugin.IS_DEVELOPMENT) {
			plugin.panel.update();
		}
	}

	public void saveRegion() throws IOException {
		Set<Path> regionDataDirectories = resolveRegionDataDirectories();
		if (regionDataDirectories.isEmpty()) {
			throw new IOException("No valid RegionData directories found for Save Changes. "
				+ "Tried cwd/runtime/env candidates. cwd=" + new File(".").getAbsolutePath());
		}

		GsonBuilder gb = plugin.gson.newBuilder();
		gb.setPrettyPrinting();
		Gson gson = gb.create();

		int successfulWrites = 0;
		List<String> failedWrites = new ArrayList<>();

		for (Path regionDataDir : regionDataDirectories) {
			Path outPath = regionDataDir.resolve(regionId + ".json");
			try (Writer wr = new BufferedWriter(Files.newBufferedWriter(outPath, StandardCharsets.UTF_8))) {
				gson.toJson(this, wr);
				successfulWrites++;
			} catch (IOException writeEx) {
				failedWrites.add(outPath.toString() + " => " + writeEx.getMessage());
			}
		}

		if (successfulWrites == 0) {
			throw new IOException("Failed writing region " + regionId + " to all targets: " + failedWrites);
		}

		if (!failedWrites.isEmpty()) {
			log.warn("Region {} saved to {} target(s), but some writes failed: {}", regionId, successfulWrites, failedWrites);
		} else {
			log.debug("Region {} saved to {} target(s)", regionId, successfulWrites);
		}
	}

	private static void addIfDirectory(Set<Path> out, Path directory) {
		if (directory == null) {
			return;
		}
		Path normalized = directory.toAbsolutePath().normalize();
		if (Files.isDirectory(normalized)) {
			out.add(normalized);
		}
	}

	private static Path envPath(String envVar) {
		String value = System.getenv(envVar);
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		return Paths.get(value.trim());
	}

	private static Path findCitizensRepoRegionDataDirUnderDropbox() {
		String userHome = System.getProperty("user.home");
		if (userHome == null || userHome.trim().isEmpty()) {
			return null;
		}

		Path dropbox = Paths.get(userHome, "Dropbox");
		if (!Files.isDirectory(dropbox)) {
			return null;
		}

		Path suffix = Paths.get("GitHub", "Citizens-2", "src", "main", "resources", "RegionData");
		try (Stream<Path> stream = Files.find(dropbox, 10, (p, attrs) -> attrs.isDirectory() && p.endsWith(suffix))) {
			return stream.findFirst().orElse(null);
		} catch (IOException ignored) {
			return null;
		}
	}

	private static Set<Path> resolveRegionDataDirectories() {
		LinkedHashSet<Path> targets = new LinkedHashSet<>();

		Path cwd = Paths.get(".").toAbsolutePath().normalize();
		addIfDirectory(targets, cwd.resolve(RELATIVE_REGIONDATA_DIRECTORY));
		addIfDirectory(targets, cwd.resolve("runelite-client").resolve(RELATIVE_REGIONDATA_DIRECTORY));

		Path runtimeRoot = envPath("CITIZENS_RUNTIME_ROOT");
		if (runtimeRoot == null) {
			runtimeRoot = envPath("GEFILTERS_RUNTIME_ROOT");
		}
		addIfDirectory(targets, runtimeRoot == null ? null : runtimeRoot.resolve(RELATIVE_REGIONDATA_DIRECTORY));

		Path repoRoot = envPath("CITIZENS_REPO_ROOT");
		addIfDirectory(targets, repoRoot == null ? null : repoRoot.resolve(RELATIVE_REGIONDATA_DIRECTORY));

		addIfDirectory(targets, findCitizensRepoRegionDataDirUnderDropbox());

		return targets;
	}

	public void updateEntities() {
		plugin.clientThread.invokeLater(() -> {
			entities.values().forEach(entity -> {
				try {
					entity.update();
				} catch (Exception ex) {
					log.error("Failed to update entity in region {} (type={}, uuid={})",
						regionId,
						entity.entityType,
						entity.uuid,
						ex);
				}
			});
		});
	}

	public void runOncePerTimePeriod(int timePeriodSeconds, int callIntervalSeconds, Consumer<Entity> callback) {
		double chance = (double) callIntervalSeconds / timePeriodSeconds;

		for (Entity entity : entities.values()) {
			if (entity.isActive() && Util.rng.nextDouble() <= chance) {
				int delayMs = (Util.getRandom(0, (callIntervalSeconds / 2) * 1000));
				executorService.schedule(() -> plugin.clientThread.invokeLater(() -> {
					try {
						callback.accept(entity);
					} catch (Exception ex) {
						log.error("Citizen callback failure in region {} (type={}, uuid={})",
							regionId,
							entity.entityType,
							entity.uuid,
							ex);
					}
				}), delayMs, TimeUnit.MILLISECONDS);
			}
		}
	}
}
