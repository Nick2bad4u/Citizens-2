package com.magnaboy;

import com.magnaboy.Util.AnimData;
import com.magnaboy.scripting.ActionType;
import com.magnaboy.scripting.ScriptAction;
import com.magnaboy.scripting.ScriptFile;
import com.magnaboy.scripting.ScriptLoader;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

@Slf4j
public class ScriptedCitizen extends Citizen<ScriptedCitizen> {
	public ScriptAction currentAction;
	public WorldPoint baseLocation;
	private ScriptFile script;
	private ExecutorService scriptExecutor;

	public ScriptedCitizen(Citizens2Plugin plugin) {
		super(plugin);
		entityType = EntityType.ScriptedCitizen;
	}

	private void submitAction(ScriptAction action, Runnable task) {
		if (scriptExecutor == null || scriptExecutor.isShutdown()) {
			return;
		}

		try {
			scriptExecutor.submit(() -> {
				if (Thread.currentThread().isInterrupted() || !isActive()) {
					return;
				}

				this.currentAction = action;
				try {
					task.run();
				} catch (Exception ex) {
					log.error("Script action {} failed for citizen {}", action == null ? null : action.action, uuid, ex);
				}

				if (!Thread.currentThread().isInterrupted()
					&& scriptExecutor != null
					&& !scriptExecutor.isShutdown()
					&& isActive()
					&& script != null) {
					buildRoutine();
				}
			});
		} catch (RejectedExecutionException ignored) {
			// executor was shut down between guard and submit
		}
	}

	public ScriptedCitizen setScript(String scriptName) {
		if (scriptName == null || scriptName.isEmpty()) {
			return this;
		}
		this.script = ScriptLoader.loadScript(plugin, scriptName);
		return this;
	}

	@Override
	public boolean despawn() {
		if (scriptExecutor != null) {
			scriptExecutor.shutdownNow();
		}
		return super.despawn();
	}

	private void refreshExecutor() {
		if (!isActive()) {
			return;
		}
		if (scriptExecutor == null || scriptExecutor.isShutdown()) {
			scriptExecutor = Executors.newSingleThreadExecutor();
			// When script restarts, make them walk to start location?
			if (baseLocation != null) {
				ScriptAction walkAction = new ScriptAction();
				walkAction.action = ActionType.WalkTo;
				walkAction.targetPosition = baseLocation;
				walkAction.secondsTilNextAction = 0f;
				addWalkAction(walkAction);
			} else {
				buildRoutine();
			}
		}
	}

	public boolean spawn() {
		boolean didSpawn = super.spawn();
		if (didSpawn) {
			refreshExecutor();
		}
		return didSpawn;
	}

	public void update() {
		refreshExecutor();
		super.update();
	}

	private void buildRoutine() {
		if (script == null || script.actions == null || script.actions.isEmpty() || scriptExecutor == null || scriptExecutor.isShutdown() || !isActive()) {
			return;
		}

		ScriptAction action = script.nextAction();
		if (action != null) {
			addAction(action);
		}
	}

	private void addAction(ScriptAction action) {
		if (action != null) {
			switch (action.action) {
				case Idle:
					submitAction(action, () -> {
						setWait(action.secondsTilNextAction);
					});
					break;
				case Say:
					addSayAction(action);
					break;
				case WalkTo:
					addWalkAction(action);
					break;
				case Animation:
					addAnimationAction(action);
					break;
				case FaceDirection:
					addRotateAction(action);
					break;
			}
		}
	}

	private void addSayAction(ScriptAction action) {
		submitAction(action, () -> {
			say(action.message);
			setWait(action.secondsTilNextAction);
		});
	}

	private void addWalkAction(ScriptAction action) {
		submitAction(action, () -> {
			if (action.targetPosition == null) {
				setWait(action.secondsTilNextAction);
				return;
			}

			WorldPoint current = getWorldLocation();
			int tilesToWalk = current == null ? 1 : action.targetPosition.distanceTo2D(current) + 1;
			if (!sleep(tilesToWalk * 100)) {
				return;
			}
			plugin.clientThread.invokeLater(() -> {
				moveTo(action.targetPosition, action.targetRotation == null ? null : action.targetRotation.getAngle(),
					false, false);
			});

			int walkSettleMillis = Math.max(250, tilesToWalk * 120);
			if (!sleep(walkSettleMillis)) {
				return;
			}

			setWait(action.secondsTilNextAction);
		});
	}

	private void addRotateAction(ScriptAction action) {
		submitAction(action, () -> {
			if (action.targetRotation == null) {
				setWait(action.secondsTilNextAction);
				return;
			}
			plugin.clientThread.invokeLater(() -> rlObject.setOrientation(action.targetRotation.getAngle()));
			if (!sleep(50)) {
				return;
			}
			setWait(action.secondsTilNextAction);
		});
	}

	private boolean sleep(int millis) {
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void addAnimationAction(ScriptAction action) {
		submitAction(action, () -> {
			if (action.animationId == null) {
				setWait(action.secondsTilNextAction);
				return;
			}

			AnimData animData = Util.getAnimData(action.animationId.getId());
			int loopCount = action.timesToLoop == null ? 1 : action.timesToLoop;
			int animDurationMillis = animData == null ? 600 : animData.realDurationMillis;
			for (int i = 0; i < loopCount; i++) {
				if (Thread.currentThread().isInterrupted() || !isActive()) {
					return;
				}
				setAnimation(action.animationId.getId());
				try {
					Thread.sleep(animDurationMillis);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			if (idleAnimationId != null) {
				setAnimation(idleAnimationId.getId());
			}
			setWait(action.secondsTilNextAction);
		});
	}

	private void setWait(Float seconds) {
		if (seconds == null) {
			return;
		}
		// We never want thread.sleep(0)
		seconds = Math.max(0.1f, seconds);
		long waitMillis = (long) (seconds * 1000L);
		if (waitMillis > Integer.MAX_VALUE) {
			waitMillis = Integer.MAX_VALUE;
		}
		sleep((int) waitMillis);
	}
}
