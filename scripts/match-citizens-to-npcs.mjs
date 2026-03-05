#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execSync } from "node:child_process";
import { pathToFileURL } from "node:url";

const DEFAULT_REGION_DIR = "./src/main/resources/RegionData";

function printHelp() {
	console.log(`Usage:
  node scripts/match-citizens-to-npcs.mjs [options]

Options:
  --cache <path>         Path to OSRS cache directory (contains main_file_cache.dat2)
  --region <id>          Only analyze one region id (e.g. 12853)
  --name <substring>     Only analyze citizens with name containing substring
  --top <n>              Top candidate NPCs per citizen (default: 3)
  --min-score <float>    Filter output rows below this score (default: 0.55)
  --json <path>          Write full results JSON to file
  --help                 Show this help

Examples:
  node scripts/match-citizens-to-npcs.mjs --cache "C:/Users/Nick/jagexcache/oldschool/LIVE" --region 12853
  node scripts/match-citizens-to-npcs.mjs --cache "C:/Users/Nick/jagexcache/oldschool/LIVE" --name ham --top 5 --json ./.github/hooks/_tmp_match.json
`);
}

function parseArgs(argv) {
	const args = {
		cache: undefined,
		region: undefined,
		name: undefined,
		top: 3,
		minScore: 0.55,
		json: undefined,
		help: false,
	};

	for (let i = 0; i < argv.length; i++) {
		const a = argv[i];
		if (a === "--help" || a === "-h") {
			args.help = true;
			continue;
		}
		if (a === "--cache") {
			args.cache = argv[++i];
			continue;
		}
		if (a === "--region") {
			args.region = Number(argv[++i]);
			continue;
		}
		if (a === "--name") {
			args.name = (argv[++i] ?? "").toLowerCase();
			continue;
		}
		if (a === "--top") {
			args.top = Math.max(1, Number(argv[++i] ?? 3));
			continue;
		}
		if (a === "--min-score") {
			args.minScore = Number(argv[++i] ?? 0.55);
			continue;
		}
		if (a === "--json") {
			args.json = argv[++i];
			continue;
		}
	}

	return args;
}

function cachePathLooksValid(cachePath) {
	if (!cachePath) {
		return false;
	}
	try {
		const dat2 = path.join(cachePath, "main_file_cache.dat2");
		const idx255 = path.join(cachePath, "main_file_cache.idx255");
		return fs.existsSync(dat2) && fs.existsSync(idx255);
	} catch {
		return false;
	}
}

function findCachePath(explicitPath) {
	const home = os.homedir();
	const localAppData = process.env.LOCALAPPDATA;
	const candidates = [
		explicitPath,
		process.env.OSRS_CACHE_PATH,
		path.join(home, "jagexcache", "oldschool", "LIVE"),
		path.join(home, "jagexcache", "oldschool", "oldschool"),
		localAppData ? path.join(localAppData, "Jagex", "Old School RuneScape") : undefined,
	].filter(Boolean);

	for (const candidate of candidates) {
		if (cachePathLooksValid(candidate)) {
			return candidate;
		}
	}

	return undefined;
}

async function loadCache2NodeModule() {
	try {
		return await import("@abextm/cache2/node");
	} catch {
		const npmRoot = execSync("npm root -g", { encoding: "utf8" }).trim();
		const globalEntry = path.join(npmRoot, "@abextm", "cache2", "dist", "node", "index.js");
		return await import(pathToFileURL(globalEntry).href);
	}
}

function safeReadJson(filePath) {
	try {
		const raw = fs.readFileSync(filePath, "utf8");
		return JSON.parse(raw);
	} catch {
		return null;
	}
}

function buildRecolorPairs(find = [], to = []) {
	const pairs = new Set();
	const n = Math.min(find.length || 0, to.length || 0);
	for (let i = 0; i < n; i++) {
		pairs.add(`${find[i]}->${to[i]}`);
	}
	return pairs;
}

function computeOrderScore(citizenModels, npcModels, overlapIds) {
	if (overlapIds.length === 0) {
		return 0;
	}
	if (overlapIds.length === 1) {
		return 1;
	}

	let sum = 0;
	const cDen = Math.max(1, citizenModels.length - 1);
	const nDen = Math.max(1, npcModels.length - 1);

	for (const id of overlapIds) {
		const ci = citizenModels.indexOf(id);
		const ni = npcModels.indexOf(id);
		if (ci < 0 || ni < 0) {
			continue;
		}
		const cn = ci / cDen;
		const nn = ni / nDen;
		sum += 1 - Math.min(1, Math.abs(cn - nn));
	}

	return sum / overlapIds.length;
}

function scoreCandidate(citizen, npc) {
	const citizenModels = citizen.modelIds || [];
	const npcModels = npc.models || [];
	if (citizenModels.length === 0 || npcModels.length === 0) {
		return null;
	}

	const cSet = new Set(citizenModels);
	const nSet = new Set(npcModels);
	const overlapIds = [...cSet].filter((id) => nSet.has(id));
	if (overlapIds.length === 0) {
		return null;
	}

	const unionSize = new Set([...cSet, ...nSet]).size;
	const jaccard = overlapIds.length / unionSize;
	const citizenCoverage = overlapIds.length / cSet.size;
	const npcCoverage = overlapIds.length / nSet.size;
	const orderScore = computeOrderScore(citizenModels, npcModels, overlapIds);

	const cPairs = buildRecolorPairs(citizen.modelRecolorFind, citizen.modelRecolorReplace);
	const nPairs = buildRecolorPairs(npc.recolorFrom, npc.recolorTo);
	let recolorJaccard = 0;
	if (cPairs.size > 0 && nPairs.size > 0) {
		const inter = [...cPairs].filter((p) => nPairs.has(p)).length;
		const uni = new Set([...cPairs, ...nPairs]).size;
		recolorJaccard = uni === 0 ? 0 : inter / uni;
	}

	let score = (0.45 * citizenCoverage) + (0.25 * npcCoverage) + (0.2 * orderScore) + (0.1 * jaccard);
	if (recolorJaccard > 0) {
		score = Math.min(1, score + (0.1 * recolorJaccard));
	}

	const missingFromCitizen = npcModels.filter((id) => !cSet.has(id));
	const extraInCitizen = citizenModels.filter((id) => !nSet.has(id));

	return {
		score,
		overlapCount: overlapIds.length,
		citizenModelCount: cSet.size,
		npcModelCount: nSet.size,
		citizenCoverage,
		npcCoverage,
		jaccard,
		orderScore,
		recolorJaccard,
		overlapIds,
		missingFromCitizen,
		extraInCitizen,
	};
}

function formatPct(v) {
	return `${(v * 100).toFixed(1)}%`;
}

async function main() {
	const args = parseArgs(process.argv.slice(2));
	if (args.help) {
		printHelp();
		return;
	}

	const cachePath = findCachePath(args.cache);
	if (!cachePath) {
		console.error("Could not find an OSRS cache path automatically.");
		console.error("Pass one explicitly with --cache <path> (must contain main_file_cache.dat2 + idx255).");
		process.exit(1);
	}

	const cache2 = await loadCache2NodeModule();
	const { loadCache, NPC } = cache2;

	const cache = await loadCache(cachePath);
	const npcsRaw = await NPC.all(cache);
	const npcs = npcsRaw
		.filter((n) => Array.isArray(n.models) && n.models.length > 0)
		.map((n) => ({
			id: Number(n.id),
			name: n.name ?? "",
			models: (n.models ?? []).map(Number),
			recolorFrom: (n.recolorFrom ?? []).map(Number),
			recolorTo: (n.recolorTo ?? []).map(Number),
			standingAnimation: Number(n.standingAnimation ?? -1),
			walkingAnimation: Number(n.walkingAnimation ?? -1),
			rotate180Animation: Number(n.rotate180Animation ?? -1),
			rotateLeftAnimation: Number(n.rotateLeftAnimation ?? -1),
			rotateRightAnimation: Number(n.rotateRightAnimation ?? -1),
			runAnimation: Number(n.runAnimation ?? -1),
			widthScale: Number(n.widthScale ?? 128),
			heightScale: Number(n.heightScale ?? 128),
		}));

	const modelToNpcIndices = new Map();
	npcs.forEach((npc, idx) => {
		for (const modelId of npc.models) {
			if (!modelToNpcIndices.has(modelId)) {
				modelToNpcIndices.set(modelId, new Set());
			}
			modelToNpcIndices.get(modelId).add(idx);
		}
	});

	const regionDir = path.resolve(DEFAULT_REGION_DIR);
	const files = fs.readdirSync(regionDir).filter((f) => f.endsWith(".json"));
	const citizens = [];
	for (const file of files) {
		const fullPath = path.join(regionDir, file);
		const region = safeReadJson(fullPath);
		if (!region) {
			continue;
		}

		if (args.region && Number(region.regionId) !== Number(args.region)) {
			continue;
		}

		for (const citizen of region.citizenRoster ?? []) {
			if (!Array.isArray(citizen.modelIds) || citizen.modelIds.length === 0) {
				continue;
			}
			if (args.name && !(citizen.name ?? "").toLowerCase().includes(args.name)) {
				continue;
			}
			citizens.push({
				file,
				regionId: Number(region.regionId),
				name: citizen.name ?? "(unnamed)",
				entityType: citizen.entityType,
				modelIds: citizen.modelIds.map(Number),
				modelRecolorFind: (citizen.modelRecolorFind ?? []).map(Number),
				modelRecolorReplace: (citizen.modelRecolorReplace ?? []).map(Number),
			});
		}
	}

	const results = [];
	for (const citizen of citizens) {
		const candidateIdx = new Set();
		for (const modelId of citizen.modelIds) {
			const s = modelToNpcIndices.get(modelId);
			if (!s) {
				continue;
			}
			for (const idx of s) {
				candidateIdx.add(idx);
			}
		}

		const scored = [];
		for (const idx of candidateIdx) {
			const npc = npcs[idx];
			const stats = scoreCandidate(citizen, npc);
			if (!stats) {
				continue;
			}
			scored.push({ npc, ...stats });
		}

		scored.sort((a, b) => {
			if (b.score !== a.score) return b.score - a.score;
			if (b.overlapCount !== a.overlapCount) return b.overlapCount - a.overlapCount;
			return Math.abs(a.citizenModelCount - a.npcModelCount) - Math.abs(b.citizenModelCount - b.npcModelCount);
		});

		const top = scored.slice(0, args.top);
		results.push({ citizen, top });
	}

	const filtered = results.filter((r) => (r.top[0]?.score ?? 0) >= args.minScore);
	console.log(`Cache path: ${cachePath}`);
	console.log(`NPC definitions loaded: ${npcs.length}`);
	console.log(`Citizens analyzed: ${citizens.length}`);
	console.log(`Rows passing min score (${args.minScore}): ${filtered.length}`);
	console.log("");

	for (const row of filtered) {
		const c = row.citizen;
		console.log(`[${c.file}] ${c.name} (${c.entityType}) models=[${c.modelIds.join(",")}]`);
		for (let i = 0; i < row.top.length; i++) {
			const m = row.top[i];
			console.log(
				`  #${i + 1} NPC ${m.npc.id} "${m.npc.name}" score=${m.score.toFixed(3)} overlap=${m.overlapCount}/${m.citizenModelCount} cov=${formatPct(m.citizenCoverage)} order=${m.orderScore.toFixed(3)}`
			);
			console.log(
				`     stand=${m.npc.standingAnimation} walk=${m.npc.walkingAnimation} rot180=${m.npc.rotate180Animation} rotL=${m.npc.rotateLeftAnimation} rotR=${m.npc.rotateRightAnimation} run=${m.npc.runAnimation} scale=(${m.npc.widthScale},${m.npc.heightScale})`
			);
			if (m.missingFromCitizen.length > 0) {
				console.log(`     missingFromCitizen=[${m.missingFromCitizen.join(",")}]`);
			}
			if (m.extraInCitizen.length > 0) {
				console.log(`     extraInCitizen=[${m.extraInCitizen.join(",")}]`);
			}
		}
		console.log("");
	}

	if (args.json) {
		const outPath = path.resolve(args.json);
		const payload = {
			meta: {
				cachePath,
				npcCount: npcs.length,
				citizenCount: citizens.length,
				top: args.top,
				minScore: args.minScore,
			},
			results,
		};
		fs.writeFileSync(outPath, JSON.stringify(payload, null, 2), "utf8");
		console.log(`Wrote JSON report: ${outPath}`);
	}
}

main().catch((err) => {
	console.error(err);
	process.exit(1);
});
