package com.magnaboy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.AABB;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import static net.runelite.api.Perspective.COSINE;
import static net.runelite.api.Perspective.SINE;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.geometry.SimplePolygon;
import net.runelite.api.model.Jarvis;

@Slf4j
public class Entity<T extends Entity<T>> {
	public Integer regionId;
	public String name;
	public String examine;
	public CitizensPlugin plugin;
	public AnimationID idleAnimationId;
	public float[] scale;
	public float[] translate;
	public List<MergedObject> mergedObjects = new ArrayList<>();
	protected RuneLiteObject rlObject;
	@Getter
	protected EntityType entityType;
	protected Integer baseOrientation;
	protected UUID uuid;
	@Getter
	private WorldPoint worldLocation;
	private int[] modelIDs;
	private int[] recolorsToFind;
	private int[] recolorsToReplace;
	protected Integer idleAnimationRawId;
	private Integer objectToRemove;
	protected Integer heightOffset;
	private volatile boolean active;
	private volatile boolean failed;

	public Entity(CitizensPlugin plugin) {
		this.plugin = plugin;
		this.rlObject = plugin.client.createRuneLiteObject();
	}

	protected static SimplePolygon calculateAABB(Client client, Model m, Integer jauOrient, int x, int y, int z, int zOff) {
		if (m == null) {
			throw new IllegalStateException("model is null");
		}
		if (jauOrient == null) {
			throw new IllegalStateException("jauOrient is null");
		}
		AABB aabb = m.getAABB(jauOrient);

		int x1 = aabb.getCenterX();
		int y1 = aabb.getCenterZ();
		int z1 = aabb.getCenterY() + zOff;

		int ex = aabb.getExtremeX();
		int ey = aabb.getExtremeZ();
		int ez = aabb.getExtremeY();

		int x2 = x1 + ex;
		int y2 = y1 + ey;
		int z2 = z1 + ez;

		x1 -= ex;
		y1 -= ey;
		z1 -= ez;

		int[] xa = new int[]{x1, x2, x1, x2, x1, x2, x1, x2};
		int[] ya = new int[]{y1, y1, y2, y2, y1, y1, y2, y2};
		int[] za = new int[]{z1, z1, z1, z1, z2, z2, z2, z2};

		int[] x2d = new int[8];
		int[] y2d = new int[8];

		Entity.modelToCanvasCpu(client, 8, x, y, z, 0, xa, ya, za, x2d, y2d);

		return Jarvis.convexHull(x2d, y2d);
	}

	private static void modelToCanvasCpu(Client client, int end, int x3dCenter, int y3dCenter, int z3dCenter, int rotate, int[] x3d, int[] y3d, int[] z3d, int[] x2d, int[] y2d) {
		final int cameraPitch = client.getCameraPitch(), cameraYaw = client.getCameraYaw(),

			pitchSin = SINE[cameraPitch], pitchCos = COSINE[cameraPitch], yawSin = SINE[cameraYaw], yawCos = COSINE[cameraYaw], rotateSin = SINE[rotate], rotateCos = COSINE[rotate],

			cx = x3dCenter - client.getCameraX(), cy = y3dCenter - client.getCameraY(), cz = z3dCenter - client.getCameraZ(),

			viewportXMiddle = client.getViewportWidth() / 2, viewportYMiddle = client.getViewportHeight() / 2, viewportXOffset = client.getViewportXOffset(), viewportYOffset = client.getViewportYOffset(),

			zoom3d = client.getScale();

		for (int i = 0; i < end; i++) {
			int x = x3d[i];
			int y = y3d[i];
			int z = z3d[i];

			if (rotate != 0) {
				int x0 = x;
				x = x0 * rotateCos + y * rotateSin >> 16;
				y = y * rotateCos - x0 * rotateSin >> 16;
			}

			x += cx;
			y += cy;
			z += cz;

			final int x1 = x * yawCos + y * yawSin >> 16, y1 = y * yawCos - x * yawSin >> 16, y2 = z * pitchCos - y1 * pitchSin >> 16, z1 = y1 * pitchCos + z * pitchSin >> 16;

			int viewX, viewY;

			if (z1 < 50) {
				viewX = Integer.MIN_VALUE;
				viewY = Integer.MIN_VALUE;
			} else {
				viewX = (viewportXMiddle + x1 * zoom3d / z1) + viewportXOffset;
				viewY = (viewportYMiddle + y2 * zoom3d / z1) + viewportYOffset;
			}

			x2d[i] = viewX;
			y2d[i] = viewY;
		}
	}

	public int getAnimationID() {
		Animation animation = rlObject.getAnimation();
		return animation == null ? -1 : animation.getId();
	}

	public boolean isCitizen() {
		return entityType == EntityType.StationaryCitizen || entityType == EntityType.WanderingCitizen || entityType == EntityType.ScriptedCitizen;
	}

	public SimplePolygon getClickbox() {
		LocalPoint location = getLocalLocation();
		if (location == null || rlObject.getModel() == null) {
			return null;
		}
		WorldView worldView = plugin.client.getTopLevelWorldView();
		if (worldView == null) {
			return null;
		}
		int plane = worldView.getPlane();
		int zOff = Perspective.getTileHeight(plugin.client, location, plane);
		return calculateAABB(plugin.client, rlObject.getModel(), rlObject.getOrientation(), location.getX(), location.getY(), plane, zOff);
	}

	public LocalPoint getLocalLocation() {
		return rlObject.getLocation();
	}

	public int getOrientation() {
		return rlObject.getOrientation();
	}

	public void setModel(Model model) {
		rlObject.setModel(model);
	}

	public void setAnimation(int animationID) {
		plugin.clientThread.invokeLater(() -> {
			Animation anim = plugin.client.loadAnimation(animationID);
			rlObject.setAnimation(anim);
		});
	}

	protected Integer getConfiguredIdleAnimationValue() {
		if (idleAnimationRawId != null) {
			return idleAnimationRawId;
		}
		return idleAnimationId == null ? null : idleAnimationId.getId();
	}

	public T setWorldLocation(WorldPoint location) {
		this.worldLocation = location;
		return (T) this;
	}

	public T setObjectToRemove(Integer objectToRemove) {
		this.objectToRemove = objectToRemove;
		return (T) this;
	}

	public T addMergedObject(MergedObject mergedObject) {
		this.mergedObjects.add(mergedObject);
		return (T) this;
	}

	public void update() {
		if (failed) {
			return;
		}

		boolean inScene = shouldRender();

		if (inScene) {
			spawn();
		} else {
			despawn();
		}
	}

	public T setScale(float[] scale) {
		this.scale = scale;
		return (T) this;
	}

	public T setTranslate(float translateX, float translateY, float translateZ) {
		this.translate = new float[]{translateX, translateY, translateZ};
		return (T) this;
	}

	public T setTranslate(float[] translate) {
		this.translate = translate;
		return (T) this;
	}

	public T setHeightOffset(Integer heightOffset) {
		this.heightOffset = heightOffset;
		return (T) this;
	}

	public T setBaseOrientation(CardinalDirection baseOrientation) {
		this.baseOrientation = baseOrientation.getAngle();
		return (T) this;
	}

	public T setBaseOrientation(Integer baseOrientation) {
		this.baseOrientation = baseOrientation;
		return (T) this;
	}

	public T setModelIDs(int[] modelIDs) {
		this.modelIDs = modelIDs;
		return (T) this;
	}

	public T setModelRecolors(int[] recolorsToFind, int[] recolorsToReplace) {
		this.recolorsToFind = recolorsToFind;
		this.recolorsToReplace = recolorsToReplace;
		return (T) this;
	}

	public T setLocation(LocalPoint location) {
		if (location == null) {
			throw new IllegalStateException("Tried to set null location");
		}
		rlObject.setLocation(location, getPlane());

		int zOffset = 0;
		CitizensConfig config = plugin.getConfig();
		if (config != null && isCitizen()) {
			zOffset += config.citizenHeightOffset();
			if (config.debugAirliftCitizens()) {
				zOffset += config.debugAirliftOffset();
			}
		}

		if (heightOffset != null) {
			zOffset += heightOffset;
		}

		if (zOffset != 0) {
			// RuneLiteObject Z grows downward in this context.
			// Positive config/entity offsets should move citizens upward for intuitive tuning.
			rlObject.setZ(rlObject.getZ() - zOffset);
		}

		WorldPoint wp = WorldPoint.fromLocal(plugin.client, location);
		setWorldLocation(wp);
		return (T) this;
	}

	public int getPlane() {
		return this.worldLocation.getPlane();
	}

	public boolean shouldRender() {
		if (worldLocation == null) {
			return false;
		}

		WorldView worldView = plugin.client.getTopLevelWorldView();
		if (worldView == null) {
			return false;
		}

		if (plugin.client.getLocalPlayer() == null) {
			return false;
		}

		if (getPlane() != worldView.getPlane()) {
			return false;
		}

		float distanceFromPlayer = distanceToPlayer();

		if (distanceFromPlayer > Util.MAX_ENTITY_RENDER_DISTANCE) {
			return false;
		}

		LocalPoint lp = LocalPoint.fromWorld(worldView, worldLocation);
		return lp != null;
	}

	public float distanceToPlayer() {
		Player player = plugin.client.getLocalPlayer();
		if (player == null || worldLocation == null) {
			return Float.MAX_VALUE;
		}
		WorldPoint playerWorldLoc = player.getWorldLocation();
		return playerWorldLoc.distanceTo(getWorldLocation());
	}

	public boolean despawn() {
		if (rlObject == null) {
			return false;
		}

		if (!active) {
			return false;
		}

		active = false;

		plugin.clientThread.invokeLater(() -> {
			if (rlObject != null && rlObject.isActive()) {
				rlObject.setActive(false);
			}
		});

		if (plugin.IS_DEVELOPMENT) {
			plugin.panel.update();
		}

		return true;
	}

	private void initModel() {
		if (rlObject.getModel() == null) {
			if (modelIDs == null || modelIDs.length == 0) {
				throw new IllegalStateException("No modelIDs configured for entity " + uuid + " (" + entityType + ")");
			}

			ArrayList<ModelData> models = new ArrayList<ModelData>();
			List<Integer> missingModelIds = new ArrayList<>();
			for (int modelID : modelIDs) {
				ModelData data = plugin.client.loadModelData(modelID);
				if (data == null) {
					log.warn("Missing model data id {} for entity {} ({})", modelID, uuid, entityType);
					missingModelIds.add(modelID);
					continue;
				}
				models.add(data);
			}

			if (!missingModelIds.isEmpty()) {
				log.warn("Entity '{}' (uuid={}, type={}) missing {} of {} model IDs {}. This can cause partial renders (e.g. missing boots).",
					name,
					uuid,
					entityType,
					missingModelIds.size(),
					modelIDs.length,
					missingModelIds);
			}

			// Merge merged objects
			for (MergedObject obj : mergedObjects) {
				ModelData data = plugin.client.loadModelData(obj.objectID);
				if (data == null) {
					log.warn("Missing merged model data id {} for entity {} ({})", obj.objectID, uuid, entityType);
					continue;
				}
				for (int i = 0; i < obj.count90CCWRotations; i++) {
					data.cloneVertices();
					data.rotateY90Ccw();
				}
				models.add(data);
			}

			if (models.isEmpty()) {
				throw new IllegalStateException("No model parts loaded for entity " + uuid + " (" + entityType + ")");
			}

			ModelData finalModel = plugin.client.mergeModels(models.toArray(new ModelData[models.size()]), models.size());
			if (finalModel == null) {
				throw new IllegalStateException("Failed to merge model parts for entity " + uuid + " (" + entityType + ")");
			}

			if (recolorsToFind != null && recolorsToReplace != null && recolorsToReplace.length > 0) {
				if (recolorsToFind.length != recolorsToReplace.length) {
					log.warn("Skipping recolors for entity '{}' (uuid={}, type={}) due mismatch: find={}, replace={}",
						name,
						uuid,
						entityType,
						recolorsToFind.length,
						recolorsToReplace.length);
				} else {
					for (int i = 0; i < recolorsToReplace.length; i++) {
						finalModel.recolor((short) recolorsToFind[i], (short) recolorsToReplace[i]);
					}
				}
			}
			if (scale != null) {
				finalModel.cloneVertices();
				finalModel.scale(-(Math.round(scale[0] * 128)), -(Math.round(scale[1] * 128)), -(Math.round(scale[2] * 128)));
			}

			if (translate != null) {
				finalModel.cloneVertices();
				finalModel.translate(-(Math.round(translate[0] * 128)), -(Math.round(translate[1] * 128)), -(Math.round(translate[2] * 128)));
			}

			rlObject.setModel(finalModel.light(64, 850, -30, -50, -30));
		}

		if (baseOrientation != null && rlObject.getOrientation() == 0) {
			rlObject.setOrientation(baseOrientation);
		}

		Animation currentAnimation = rlObject.getAnimation();
		Integer idleAnimationValue = getConfiguredIdleAnimationValue();
		if (idleAnimationValue != null && currentAnimation == null) {
			setAnimation(idleAnimationValue);
		}

		rlObject.setShouldLoop(true);
	}

	public String debugName() {
		float dist = distanceToPlayer();
		String shortId = uuid == null ? "null" : uuid.toString().substring(0, Math.min(6, uuid.toString().length()));
		return "N:" + name + " T:" + entityType + " ID:" + shortId + " D:" + dist;
	}

	public void validate() {
		if (uuid == null) {
			throw new IllegalStateException(debugName() + " has no uuid.");
		}
		if (regionId == null) {
			throw new IllegalStateException(debugName() + " has no regionId.");
		}
	}

	private void initLocation() {
		LocalPoint initializedLocation = LocalPoint.fromWorld(plugin.client, worldLocation);
		if (initializedLocation == null) {
			throw new IllegalStateException("Tried to spawn entity with no initializedLocation: " + debugName());
		}
		setLocation(initializedLocation);
	}

	public boolean spawn() {
		if (this.isActive()) {
			return false;
		}

		if (failed) {
			return false;
		}

		try {
			initModel();
			initLocation();
			if (objectToRemove != null) {
				removeOtherObjects();
			}
		} catch (Exception ex) {
			failed = true;
			active = false;
			log.error("Failed to spawn entity (uuid={}, type={}, region={}); disabling further updates", uuid, entityType, regionId, ex);
			return false;
		}

		Integer idleAnimationValue = getConfiguredIdleAnimationValue();
		if (idleAnimationValue != null) {
			setAnimation(idleAnimationValue);
		}

		active = true;

		plugin.clientThread.invokeLater(() -> {
			rlObject.setActive(true);
		});

		if (plugin.IS_DEVELOPMENT) {
			plugin.panel.update();
		}

		return true;
	}

	public boolean isActive() {
		return active;
	}

	public boolean rotateObject(double intx, double inty) {
		if (intx == 0 && inty == 0) {
			return true;
		}
		int targetOrientation = Util.radToJau(Math.atan2(intx, inty));
		int currentOrientation = rlObject.getOrientation();

		int dJau = (targetOrientation - currentOrientation) % Util.JAU_FULL_ROTATION;
		if (dJau != 0) {
			final int JAU_HALF_ROTATION = 1024;
			final int JAU_TURN_SPEED = 32;
			int dJauCW = Math.abs(dJau);

			if (dJauCW > JAU_HALF_ROTATION) {
				dJau = (currentOrientation - targetOrientation) % Util.JAU_FULL_ROTATION;
			} else if (dJauCW == JAU_HALF_ROTATION) {
				dJau = dJauCW;
			}

			if (Math.abs(dJau) > JAU_TURN_SPEED) {
				dJau = Integer.signum(dJau) * JAU_TURN_SPEED;
			}

			int newOrientation = (Util.JAU_FULL_ROTATION + rlObject.getOrientation() + dJau) % Util.JAU_FULL_ROTATION;

			rlObject.setOrientation(newOrientation);
			dJau = (targetOrientation - newOrientation) % Util.JAU_FULL_ROTATION;
		}

		return dJau == 0;
	}

	public T setIdleAnimation(AnimationID idleAnimationId) {
		this.idleAnimationId = idleAnimationId;
		return (T) this;
	}

	public T setIdleAnimationRawId(Integer idleAnimationRawId) {
		this.idleAnimationRawId = idleAnimationRawId;
		return (T) this;
	}

	public T setUUID(UUID uuid) {
		if (this.uuid == null) {
			this.uuid = uuid;
		}
		return (T) this;
	}

	public T setRegion(int regionId) {
		this.regionId = regionId;
		return (T) this;
	}

	@Override
	public boolean equals(Object o) {
		if (o == this) {
			return true;
		}

		if (!(o instanceof Entity)) {
			return false;
		}

		Entity compare = (Entity) o;
		return Objects.equals(this.uuid, compare.uuid);
	}

	public String getModelIDsString() {
		return Util.intArrayToString(modelIDs);
	}

	public String getRecolorFindString() {
		return Util.intArrayToString(recolorsToFind);
	}

	public String getRecolorReplaceString() {
		return Util.intArrayToString(recolorsToReplace);
	}

	private void removeOtherObjects() {
		if (worldLocation == null) {
			return;
		}

		WorldView worldView = plugin.client.getTopLevelWorldView();
		if (worldView == null) {
			return;
		}

		Scene scene = worldView.getScene();
		if (scene == null || scene.getTiles() == null) {
			return;
		}

		int plane = worldView.getPlane();
		if (plane < 0 || plane >= scene.getTiles().length) {
			return;
		}

		Tile[][] tiles = scene.getTiles()[plane];
		if (tiles == null) {
			return;
		}

		LocalPoint lp = LocalPoint.fromWorld(worldView, worldLocation);
		if (lp == null) {
			return;
		}

		int sceneX = lp.getSceneX();
		int sceneY = lp.getSceneY();
		if (sceneX < 0 || sceneX >= tiles.length || tiles[sceneX] == null || sceneY < 0 || sceneY >= tiles[sceneX].length) {
			return;
		}

		Tile tile = tiles[sceneX][sceneY];
		if (tile == null) {
			return;
		}

		for (GameObject gameObject : tile.getGameObjects()) {
			if (gameObject == null) {
				continue;
			}
			if (gameObject.getId() == objectToRemove) {
				// Currently it's not possible to re-add the Game Object outside of an area load
				scene.removeGameObject(gameObject);
			}
		}

	}
}
