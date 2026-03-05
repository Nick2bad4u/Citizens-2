// @ts-nocheck
const { readdirSync, readFileSync } = require("node:fs");
const path = require("node:path");

const BASE_PATH = "./src/main/resources/RegionData";
const NPC_MODEL_IDS_PATH = "./scripts/npcs-models.json";

// Derived from commit bbf0c88 (Model ID updates #51) where legacy IDs were remapped.
const LEGACY_REMAPS = {
	163: 27154,
	167: 26634,
	170: 26619,
	173: 27139,
	181: 26630,
	151: 26632,
	162: 26630,
	274: 28346,
	292: 28515,
	250: 4123,
	215: 391,
	10218: 54275,
	348: 3848,
	358: 14395,
	456: 18554,
	428: 14421,
	471: 18914,
	10706: 433,
	11767: 54165,
	28337: 28512
};

function readJson(filePath) {
	return JSON.parse(readFileSync(filePath, "utf8"));
}

const npcModelIds = new Set(readJson(NPC_MODEL_IDS_PATH));
const files = readdirSync(BASE_PATH).filter((f) => f.endsWith(".json"));

const invalidRows = [];
const legacyRows = [];
const lastSlotRows = [];

for (const file of files) {
	const region = readJson(path.join(BASE_PATH, file));
	for (const citizen of region.citizenRoster ?? []) {
		const ids = citizen.modelIds ?? [];

		if (ids.length > 0 && ids.length >= 8 && citizen.entityType !== "Scenery") {
			lastSlotRows.push({
				file,
				regionId: region.regionId,
				name: citizen.name,
				entityType: citizen.entityType,
				id: ids[ids.length - 1],
				count: ids.length
			});
		}

		ids.forEach((id, index) => {
			if (!npcModelIds.has(id)) {
				invalidRows.push({
					file,
					regionId: region.regionId,
					name: citizen.name,
					id,
					index: index + 1,
					count: ids.length,
					suggestion: LEGACY_REMAPS[id] ?? null
				});
			}

			if (LEGACY_REMAPS[id]) {
				legacyRows.push({
					file,
					regionId: region.regionId,
					name: citizen.name,
					id,
					recommended: LEGACY_REMAPS[id],
					index: index + 1,
					count: ids.length
				});
			}
		});
	}
}

console.log("=== Invalid Model IDs (not in scripts/npcs-models.json) ===");
if (invalidRows.length === 0) {
	console.log("None");
} else {
	invalidRows.forEach((r) => {
		const suffix = r.suggestion ? ` | suggested remap: ${r.id} -> ${r.suggestion}` : "";
		console.log(`- ${r.name} (${r.file}, region ${r.regionId}) uses invalid id ${r.id} at slot ${r.index}/${r.count}${suffix}`);
	});
}

console.log("\n=== Legacy IDs still present (likely stale post-#51) ===");
if (legacyRows.length === 0) {
	console.log("None");
} else {
	legacyRows.forEach((r) => {
		console.log(`- ${r.name} (${r.file}, region ${r.regionId}) has ${r.id} at slot ${r.index}/${r.count} | recommended ${r.recommended}`);
	});
}

const lastIdCounts = new Map();
for (const row of lastSlotRows) {
	lastIdCounts.set(row.id, (lastIdCounts.get(row.id) ?? 0) + 1);
}

const rareLastSlotRows = lastSlotRows.filter((r) => (lastIdCounts.get(r.id) ?? 0) <= 2);

console.log("\n=== Rare last-slot model IDs (heuristic boot-check candidates) ===");
if (rareLastSlotRows.length === 0) {
	console.log("None");
} else {
	rareLastSlotRows
		.sort((a, b) => a.id - b.id)
		.forEach((r) => {
			console.log(`- ${r.name} (${r.file}, region ${r.regionId}, ${r.entityType}) last-slot id ${r.id} appears ${lastIdCounts.get(r.id)} time(s)`);
		});
}

console.log("\nTip: run `node scripts/model-id-lookup.js <id>` for deep per-ID usage context.");
