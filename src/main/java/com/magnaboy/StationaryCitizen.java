package com.magnaboy;

public class StationaryCitizen extends Citizen<StationaryCitizen> {
	public StationaryCitizen(Citizens2Plugin plugin) {
		super(plugin);
		entityType = EntityType.StationaryCitizen;
	}
}
