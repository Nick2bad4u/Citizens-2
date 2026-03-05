// @ts-nocheck
const { readdirSync, readFileSync } = require("node:fs");
const path = require("node:path");

const BASE_PATH = "./src/main/resources/RegionData";
const NPC_MODEL_IDS_PATH = "./scripts/npcs-models.json";

function readJson(filePath) {
	return JSON.parse(readFileSync(filePath, "utf8"));
}

function usage() {
	console.log("Usage: node scripts/model-id-lookup.js <modelId>");
	console.log("Example: node scripts/model-id-lookup.js 317");
}

const arg = process.argv[2];
if (!arg) {
	usage();
	process.exit(1);
}

const modelId = Number(arg);
if (!Number.isInteger(modelId)) {
	console.error(`Invalid model id: ${arg}`);
	process.exit(1);
}

const npcModelIds = new Set(readJson(NPC_MODEL_IDS_PATH));
const files = readdirSync(BASE_PATH).filter((f) => f.endsWith(".json"));

const matches = [];
for (const file of files) {
	const region = readJson(path.join(BASE_PATH, file));
	for (const citizen of region.citizenRoster ?? []) {
		const ids = citizen.modelIds ?? [];
		ids.forEach((id, index) => {
			if (id === modelId) {
				matches.push({
					file,
					regionId: region.regionId,
					name: citizen.name,
					entityType: citizen.entityType,
					index,
					count: ids.length,
					modelIds: ids.join(",")
				});
			}
		});
	}
}

console.log(`Model ID: ${modelId}`);
console.log(`Valid in scripts/npcs-models.json: ${npcModelIds.has(modelId)}`);
console.log("");

if (matches.length === 0) {
	console.log("No citizen uses this model ID.");
	process.exit(0);
}

console.log(`Used by ${matches.length} citizen entries:`);
for (const m of matches) {
	console.log(`- ${m.name} (${m.file}, region ${m.regionId}, ${m.entityType}) index ${m.index + 1}/${m.count}`);
	console.log(`  modelIds: [${m.modelIds}]`);
}

console.log("");
console.log("Note: model IDs do not include a reliable public 'model name' in this repo/API.");
console.log("Use index position + split-model debug view to identify which body part a given ID controls.");
