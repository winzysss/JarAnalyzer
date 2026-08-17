package com.jaranalyzer.scan;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Loads and saves the blacklist as editable JSON under
 * {@code %APPDATA%\JarAnalyzer\blacklist.json}.
 *
 * <p>Keeping it on disk rather than in the source is the point: the operator is
 * expected to add terms as new cheats appear, without a rebuild.
 */
public final class BlacklistStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Folder used before the tool was renamed to Jar Analyzer. */
	private static final String LEGACY_DIR = "WinzysJarFucker";

	private BlacklistStore() {
	}

	public static File configDir() {
		String appData = System.getenv("APPDATA");
		File base = (appData != null && !appData.isEmpty())
				? new File(appData)
				: new File(System.getProperty("user.home", "."));
		File dir = new File(base, "JarAnalyzer");
		if (!dir.exists()) {
			dir.mkdirs();
			// The rename must not throw away hand-edited terms. Anything the old
			// folder held is copied across once, on the first run under the new
			// name; the old folder is left alone so nothing is destroyed if this
			// goes wrong.
			migrateFrom(new File(base, LEGACY_DIR), dir);
		}
		return dir;
	}

	private static void migrateFrom(File oldDir, File newDir) {
		if (!oldDir.isDirectory()) return;
		File[] kids = oldDir.listFiles();
		if (kids == null) return;
		for (File f : kids) {
			if (!f.isFile()) continue;
			try {
				java.nio.file.Files.copy(f.toPath(), new File(newDir, f.getName()).toPath());
			} catch (IOException ignored) {
				// Best effort: a failed copy just means the defaults get seeded.
			}
		}
	}

	public static File blacklistFile() {
		return new File(configDir(), "blacklist.json");
	}

	/** Reads the saved blacklist, seeding the defaults on first run. */
	public static Blacklist load() {
		File f = blacklistFile();
		if (!f.isFile()) {
			Blacklist def = defaults();
			try {
				save(def);
			} catch (IOException ignored) {
				// First-run seeding is best effort; an unwritable APPDATA still
				// leaves a usable in-memory blacklist.
			}
			return def;
		}
		try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
			List<BlacklistEntry> list = GSON.fromJson(r,
					new TypeToken<List<BlacklistEntry>>() { }.getType());
			if (list == null || list.isEmpty()) return defaults();
			migrateDescriptions(list);
			return new Blacklist(list);
		} catch (Exception e) {
			// Corrupt file: keep it for the user to inspect, carry on with defaults.
			f.renameTo(new File(configDir(), "blacklist.corrupt-" + System.currentTimeMillis() + ".json"));
			return defaults();
		}
	}

	/**
	 * Turns the descriptions the built-in terms used to carry into message keys.
	 *
	 * <p>Built-in descriptions were once stored as Turkish sentences, so a list
	 * saved by an earlier version stays Turkish no matter which language the window
	 * is set to. Matching the exact former wording rather than "any built-in term"
	 * is what makes this safe to run on every load: a description the user wrote or
	 * edited will not match, so their text is never overwritten.
	 */
	private static void migrateDescriptions(List<BlacklistEntry> list) {
		java.util.Map<String, String> old = new java.util.HashMap<>();
		old.put("Bilinen hile client'ı", "wjf.blc.client");
		old.put("Hile client'ı adı (bağlamla birlikte)", "wjf.blc.clientCtx");
		old.put("Aura / aim hile modülü", "wjf.blc.aura");
		old.put("Savaş avantajı modülü", "wjf.blc.combat");
		old.put("Hareket hile modülü", "wjf.blc.movement");
		old.put("Görsel hile modülü", "wjf.blc.render");
		old.put("Otomasyon hile modülü", "wjf.blc.player");
		old.put("Dünya manipülasyonu", "wjf.blc.world");
		old.put("Anticheat atlatma", "wjf.blc.bypass");
		old.put("Anticheat adını hedef alan metin", "wjf.blc.bypassName");
		old.put("Hile client mimarisi", "wjf.blc.structure");
		old.put("Kimlik / oturum hırsızlığı", "wjf.blc.stealer");
		old.put("Veri sızdırma adresi", "wjf.blc.exfil");
		old.put("Bilinen hile paketi", "wjf.blc.package");

		for (BlacklistEntry e : list) {
			String key = old.get(e.getDescription());
			if (key != null) e.setDescription(key);
		}
	}

	public static void save(Blacklist blacklist) throws IOException {
		saveTo(blacklist, blacklistFile());
	}

	/**
	 * How many of the built-in CRITICAL/HIGH terms are missing from the given
	 * blacklist.
	 *
	 * <p>The blacklist is an editable JSON file, which is the point — an operator
	 * adds terms as new cheats appear. But it also means someone screensharing a
	 * check could quietly gut the default list to sneak a known cheat past it. A
	 * non-zero count here is surfaced in the UI so that tampering is visible rather
	 * than silent. Only the strong tiers are counted; removing a user's own edits
	 * or a low-severity term is not tampering.
	 */
	public static int missingDefaultTerms(Blacklist current) {
		java.util.Set<String> have = new java.util.HashSet<>();
		for (BlacklistEntry e : current.entries()) {
			have.add(e.getPattern().toLowerCase(java.util.Locale.ROOT));
		}
		int missing = 0;
		for (BlacklistEntry def : defaults().entries()) {
			if (def.getSeverity().weight() < Severity.HIGH.weight()) continue;
			if (!have.contains(def.getPattern().toLowerCase(java.util.Locale.ROOT))) missing++;
		}
		return missing;
	}

	public static void saveTo(Blacklist blacklist, File target) throws IOException {
		File parent = target.getParentFile();
		if (parent != null && !parent.exists()) parent.mkdirs();
		try (Writer w = Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8)) {
			GSON.toJson(blacklist.entries(), w);
		}
	}

	public static Blacklist importFrom(File source) throws IOException {
		try (Reader r = Files.newBufferedReader(source.toPath(), StandardCharsets.UTF_8)) {
			List<BlacklistEntry> list = GSON.fromJson(r,
					new TypeToken<List<BlacklistEntry>>() { }.getType());
			if (list == null) list = new ArrayList<>();
			return new Blacklist(list);
		}
	}

	// =====================================================================
	//  Defaults
	// =====================================================================

	private static void add(List<BlacklistEntry> out, String category, Severity sev,
			MatchKind kind, String description, String... patterns) {
		for (String p : patterns) {
			out.add(new BlacklistEntry(p, kind, sev, category, description));
		}
	}

	/**
	 * Seed list.
	 *
	 * <p>One rule decides what belongs here: <b>seeing the term in a JAR must be
	 * strange on its own.</b> Ordinary English words that happen to name a cheat
	 * module are excluded no matter how well known the module is, because they
	 * occur constantly in innocent code — {@code phase} is a variable in every
	 * animation loop, {@code step} is in every movement mod, {@code esp} is three
	 * letters, {@code Impact} and {@code Rise} are words. Earlier versions carried
	 * all of those and flagged the unmodified game and half of Maven Central.
	 *
	 * <p>Ambiguous client names are kept only in a compound form
	 * ({@code VapeClient}, not {@code Vape}) or behind a regex that requires
	 * context. Generic JVM machinery — reflection, {@code ProcessBuilder},
	 * {@code defineClass}, mixin and coremod manifests — is gone entirely: it is
	 * present in nearly every mod, legitimate or not, and the manifest analyser
	 * already reports agents and tweakers on its own.
	 *
	 * <p>Obfuscator names are likewise absent; {@link ObfuscationAnalyzer} detects
	 * those directly and reporting them twice only inflated scores.
	 */
	public static Blacklist defaults() {
		List<BlacklistEntry> e = new ArrayList<>(300);

		// ---- Named cheat clients ---------------------------------------
		// Product names. Nothing else is called these, so a match is decisive.
		add(e, "Client", Severity.CRITICAL, MatchKind.WORD,
				"wjf.blc.client",
				"LiquidBounce", "Wurst", "Aristois", "MeteorClient", "Meteor Client",
				"FutureClient", "Future Client", "ImpactClient", "Impact Client",
				"RiseClient", "Rise Client", "Novoline", "Astolfo", "Huzuni", "Nodus",
				"Phobos", "SalHack", "RusherHack", "BleachHack",
				"KamiBlue", "Kami Blue", "ForgeHax", "Tenacity", "ThunderHack",
				"TrollHack", "EarthHack", "Konas", "PyroClient", "FluxClient",
				"InertiaClient", "VapeClient", "VapeCloud", "VapeV4", "VapeLite",
				"RavenB3", "RavenB4", "RavenXE", "DripLite", "FDPClient",
				"MoonClient", "PolarClient", "SpectreClient", "ZephyrClient",
				"Atrocity", "Ypsilon", "NightX", "WeepCraft", "CreepySalHack",
				"MelonHack", "SystemHack", "ZeroHack", "NineHack", "FemHack",
				"CurryMod", "PepsiMod", "Doomsday", "KryptonClient", "MarlowClient",
				"PolinexLoader", "Taunahi", "Baritone", "Skidfuscator");

		// Names that are also ordinary words: only counted next to client context.
		add(e, "Client", Severity.HIGH, MatchKind.REGEX,
				"wjf.blc.clientCtx",
				"(?i)\\b(vape|raven|sigma|inertia|flux|pyro|smoke|reaper|chorus|ikea)\\b[\\s._-]{0,4}(client|hack|cheat|ware)",
				"(?i)\\b(hacked|cheat)[\\s._-]{0,2}client\\b",
				"(?i)\\bskid(ded|suite|fuscator)\\b");

		// ---- Combat ----------------------------------------------------
		add(e, "Combat", Severity.CRITICAL, MatchKind.WORD, "wjf.blc.aura",
				"KillAura", "ForceField", "Forcefield", "MultiAura",
				"SingleAura", "SwitchAura", "TriggerBot", "Aimbot",
				"AimAssist", "SilentAim", "SilentAura", "ComboAura", "BowAimbot",
				"CrystalAura", "AutoCrystal", "AnchorAura", "BedAura", "ClickAura", "TPAura");

		add(e, "Combat", Severity.HIGH, MatchKind.WORD, "wjf.blc.combat",
				"AutoClicker", "ReachHack", "AttackReach", "CombatReach", "HitBoxExpand",
				"AntiKnockback", "AntiKB", "VelocityCancel", "NoHitDelay", "NoAttackDelay",
				"AutoBlock", "SilentBlock", "FakeLag", "TickShift", "AutoSoup", "AutoPot",
				"AutoGapple", "AutoTotem", "TargetStrafe", "AutoSword");

		// ---- Movement --------------------------------------------------
		add(e, "Movement", Severity.HIGH, MatchKind.WORD, "wjf.blc.movement",
				"FlyHack", "SpeedHack", "BunnyHop", "BHop", "NoFall", "SafeWalk",
				"WaterWalk", "AirWalk", "AirJump", "InfiniteJump", "HighJump", "LongJump",
				"SuperJump", "SpiderHack", "WallClimb", "NoSlowDown", "ElytraFly",
				"BoatFly", "PacketFly", "VClip", "HClip", "TimerHack",
				"TickAccel", "TickAccelerate");

		// ---- Render ----------------------------------------------------
		add(e, "Render", Severity.CRITICAL, MatchKind.WORD, "wjf.blc.render",
				"XRayHack", "XrayMod", "X-Ray", "WallHack", "ChestESP",
				"PlayerESP", "MobESP", "StorageESP", "EntityESP", "Chams", "Tracers",
				"TrueSight", "NameProtect", "PlayerFinder");

		// ---- Player / world -------------------------------------------
		add(e, "Player", Severity.HIGH, MatchKind.WORD, "wjf.blc.player",
				"ChestStealer", "AutoArmor", "AutoTool", "FastPlace", "FastBreak",
				"NoBreakDelay", "PacketMine", "GhostHand", "FastEat", "AutoFish",
				"NoCooldown", "CooldownBypass", "InvCleaner", "InventoryCleaner",
				"AutoReplenish", "PortalGodMode");

		// "Scaffold", "NoClip" ve "Wolfram" kasıtlı olarak yok: "scaffolding" vanilla
		// bir blok, "noclip" her oyun motorunda geçen genel bir terim, "Wolfram" ise
		// bilimsel yazılımda sık rastlanan bir isim.
		add(e, "World", Severity.CRITICAL, MatchKind.WORD, "wjf.blc.world",
				"Nuker", "CivBreak", "BedFucker", "BedBreaker",
				"ServerCrasher", "Disabler", "SurroundBreak", "BurrowBreak");

		// ---- Anticheat evasion -----------------------------------------
		add(e, "Bypass", Severity.CRITICAL, MatchKind.WORD, "wjf.blc.bypass",
				"AntiCheatBypass", "AntiAntiCheat", "BypassAC", "PacketBypass",
				"ReachBypass", "HitBypass", "CombatBypass", "SprintBypass",
				"HitDelayBypass", "PingSpoof", "PacketCancel", "SilentCancel",
				"CancelPacket", "FakeHit", "SilentHit", "GhostHit");

		add(e, "Bypass", Severity.HIGH, MatchKind.REGEX, "wjf.blc.bypassName",
				"(?i)\\bbypass(es|ed|ing)?\\b[\\s._-]{0,4}\\b(nocheatplus|ncp|aac|matrix|spartan|vulcan|grim|intave|verus|polar|karhu|themis|horizon)\\b",
				"(?i)\\b(nocheatplus|aac|matrix|spartan|vulcan|grimac|intave|verus|karhu)\\b.{0,24}\\b(bypass|disable|defeat)\\b");

		// ---- Cheat client architecture ---------------------------------
		// "ModuleManager" is deliberately absent: it is a class name in a great
		// deal of perfectly ordinary software.
		add(e, "Structure", Severity.HIGH, MatchKind.WORD, "wjf.blc.structure",
				"HackManager", "ClickGUI", "ClickGui", "HackMenu", "CheatMenu",
				"ToggleHack", "EnableHack", "DisableHack", "HackedClient",
				"CheatClient", "HackClient", "HackClassLoader");

		// ---- Malware ---------------------------------------------------
		add(e, "Malware", Severity.CRITICAL, MatchKind.WORD, "wjf.blc.stealer",
				"TokenGrabber", "TokenStealer", "PasswordStealer", "SessionStealer",
				"CookieStealer", "Keylogger", "KeyLogger", "ClipboardStealer",
				"WalletStealer", "SeedPhrase", "InfoStealer", "RatClient");

		add(e, "Malware", Severity.CRITICAL, MatchKind.REGEX, "wjf.blc.exfil",
				"(?i)https?://(discord(app)?\\.com/api/webhooks|api\\.telegram\\.org/bot)",
				"(?i)\\\\AppData\\\\Roaming\\\\discord(canary|ptb)?\\\\Local\\s*Storage\\\\leveldb");

		// ---- Known cheat package roots ---------------------------------
		add(e, "Package", Severity.CRITICAL, MatchKind.LITERAL, "wjf.blc.package",
				"org/rusherhack", "me/earth/earthhack", "thunder/hack",
				"com/chorus/impl/modules", "keystrokesmod/module/impl",
				"net/mommymarlow", "lol/polinexclient", "net/taunahi",
				"org/vined/ikea", "com/bizcub/autoAim", "net/minecraft/client/creative/hack");

		return new Blacklist(e);
	}
}
