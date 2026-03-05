// @ts-nocheck
const { readdirSync, readFileSync, writeFileSync } = require("node:fs");
const path = require("node:path");

const BASE_PATH = "./src/main/resources/RegionData";

// Keep this list conservative and evidence-based.
const TARGETED_REPLACEMENTS = [
	{ name: "Ambatu", from: 201, to: 185 },
	{ name: "Rufus", from: 26630, to: 185 }
];

function readJson(filePath) {
	return JSON.parse(readFileSync(filePath, "utf8"));
}

const files = readdirSync(BASE_PATH).filter((f) => f.endsWith(".json"));
let changedFiles = 0;
let replacements = 0;

for (const file of files) {
	const fullPath = path.join(BASE_PATH, file);
	const region = readJson(fullPath);
	let fileChanged = false;

	for (const citizen of region.citizenRoster ?? []) {
		if (!Array.isArray(citizen.modelIds)) {
			continue;
		}

		for (const rule of TARGETED_REPLACEMENTS) {
			if (citizen.name !== rule.name) {
				continue;
			}

			if (citizen.modelIds.includes(rule.from)) {
				citizen.modelIds = citizen.modelIds.map((id) => (id === rule.from ? rule.to : id));
				fileChanged = true;
				replacements++;
				console.log(`[boots-fix] ${citizen.name} (${file}): ${rule.from} -> ${rule.to}`);
			}
		}
	}

	if (fileChanged) {
		changedFiles++;
		writeFileSync(fullPath, JSON.stringify(region, null, 4) + "\n", "utf8");
	}
}

if (replacements === 0) {
	console.log("[boots-fix] No changes needed.");
} else {
	console.log(`[boots-fix] Applied ${replacements} replacement(s) across ${changedFiles} file(s).`);
}
