package com.jaranalyzer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class CheatDetector {

	private static volatile java.util.regex.Pattern cachedKeywordPattern = null;
	private static volatile java.util.Map<String, String> cachedLowerToOriginal = null;
	private static volatile Set<String> cachedAllKeywords = null;

	private static final java.util.Set<String> LIBRARY_JAR_PREFIXES = new java.util.HashSet<>(java.util.Arrays.asList(
			"guava-", "gson-", "jackson-", "commons-", "log4j-", "slf4j-", "asm-", "netty-",
			"kotlin-stdlib", "kotlinx-", "junit-", "mockito-", "hamcrest-", "javax.",
			"jopt-simple-", "jansi-", "jna-", "jffi-", "oshi-", "reactive-streams",
			"httpclient", "httpcore", "httpmime", "fluent-hc", "xercesImpl", "xml-apis",
			"dom4j-", "jaxen-", "snakeyaml-", "jzlib-", "akka-", "scala-",
			"protobuf-java", "grpc-", "protobuf-", "opencensus-", "perfidies-",
			"checker-qual-", "error_prone-", "j2objc-", "animal-sniffer-",
			"mozillaparser-", "rhino-", "nashorn-", "jline-", "jansi-",
			"lwjgl-", "lwjgl3-", "stb-", "joml-", "uncsimplelibs-",
			"brigadier-", "datafixerupper-", "authlib-", "launcher-",
			"fastutil-", "it.unimi", "trove4j-", "coreml-",
			"text2speech", "jlayer-", "tritonus-", "vorbisspi-",
			"mp3spi-", "com.sun", "sun.misc", "java-objc-",
			"native-utils-", "oshi-core", "libffi", "jctools-",
			"abstract-fixture", "testng-", "spock-core-", "groovy-"
	));

	public static boolean isLikelyLibraryJar(String jarName) {
		String lower = jarName.toLowerCase();
		for (String prefix : LIBRARY_JAR_PREFIXES) {
			if (lower.startsWith(prefix)) return true;
		}
		if (lower.endsWith("-sources.jar") || lower.endsWith("-javadoc.jar")) return true;
		if (lower.startsWith("minecraftforge") || lower.startsWith("fml") || lower.startsWith("forge-")) return true;
		if (lower.equals("1.jar") || lower.equals("client.jar") || lower.equals("server.jar")) return false;
		return false;
	}

	private static synchronized void ensureCompiledPattern(Set<String> customKeywords) {
		Set<String> allKeywords = new HashSet<>(CHEAT_KEYWORDS);
		if (customKeywords != null) {
			for (String kw : customKeywords) {
				String trimmed = kw.trim();
				if (trimmed.length() > 0) {
					allKeywords.add(trimmed);
				}
			}
		}
		if (cachedAllKeywords != null && cachedAllKeywords.equals(allKeywords) && cachedKeywordPattern != null) {
			return;
		}
		java.util.Map<String, String> lowerToOriginal = new java.util.HashMap<>(allKeywords.size() * 2);
		for (String kw : allKeywords) {
			lowerToOriginal.put(kw.toLowerCase(), kw);
		}
		StringBuilder patternBuilder = new StringBuilder();
		for (String kw : allKeywords) {
			if (patternBuilder.length() > 0) patternBuilder.append("|");
			patternBuilder.append(java.util.regex.Pattern.quote(kw));
		}
		cachedKeywordPattern = java.util.regex.Pattern.compile(
				patternBuilder.toString(), java.util.regex.Pattern.CASE_INSENSITIVE);
		cachedLowerToOriginal = lowerToOriginal;
		cachedAllKeywords = allKeywords;
	}

	public static void clearCachedPattern() {
		cachedKeywordPattern = null;
		cachedLowerToOriginal = null;
		cachedAllKeywords = null;
	}

	public static class DetectionResult {
		public final String jarPath;
		public final String className;
		public final int lineNumber;
		public final String keyword;
		public final String lineContent;
		public final RiskLevel risk;

		public DetectionResult(String jarPath, String className, int lineNumber, String keyword, String lineContent,
				RiskLevel risk) {
			this.jarPath = jarPath;
			this.className = className;
			this.lineNumber = lineNumber;
			this.keyword = keyword;
			this.lineContent = lineContent;
			this.risk = risk;
		}
	}

	public enum RiskLevel {
		HIGH, MEDIUM, LOW
	}

	public static final Set<String> CHEAT_KEYWORDS = new HashSet<>(Arrays.asList(
			// Combat - Aura/Aim
			"KillAura", "Killaura", "killaura", "Forcefield", "MultiAura", "SwitchAura", "SingleAura", "TPAura",
			"ClickAura", "Triggerbot", "AimAssist", "Aimbot", "SilentAim", "SmoothAim",
			"ComboAura", "BowAimbot", "BowTarget", "SilentAura", "FakeLag", "TickShift", "BackTrack",
			// Combat - Hit/Attack
			"AntiKnockback", "NoKB", "AutoClicker", "FastBow", "KeepSprint",
			"Hitboxes", "BlockTap", "AutoSoup", "AutoPot", "AutoWeapon", "TargetHUD", "TargetStrafe",
			"AutoSword", "AutoGapple", "WeaponQA", "Criticals", "NoHitDelay", "SwingSpeed",
			// Combat - Reach
			"ReachHack", "AttackReach", "HitReach", "CombatReach", "ReachDisplay",
			// Movement
			"BHop", "BunnyHop", "WaterWalk", "Spider", "WallClimb", "NoFall",
			"SafeWalk", "FastLadder", "HighJump", "Glide",
			"ElytraFly", "Parkour", "LongJump", "AirWalk", "FastWalk", "BoatFly", "EntitySpeed", "IceSpeed",
			"LadderJump", "SlimeJump", "SuperJump", "VClip", "HClip", "FastStop", "InfiniteJump",
			"AutoSneak", "NoSlow", "NoJumpDelay", "AirJump", "SneakSpeed", "FlyHack", "SpeedHack",
			"TimerHack", "TimerSpeed", "TickRate", "PacketSpeed",
			// Render
			"X-Ray", "XRay", "Tracers", "Nametags", "Chams", "Freecam",
			"Trajectories", "AntiBlind", "TrueSight", "Wallhack",
			"Nameprotect", "NoHurtCam", "CameraClip",
			"NoCameraClip", "PlayerFinder",
			// Player
			"FastPlace", "FastBreak", "AutoEat", "AutoArmor", "ChestStealer", "AutoTotem", "AutoTool",
			"AutoLoot", "InvManager", "FastUse", "NoWeb", "AutoDisconnect", "AutoLeave", "AutoLog", "AutoReconnect",
			"AutoRespawn", "AutoShear", "GhostHand", "NoBreakDelay", "NoGlitchBlocks", "PacketMine", "PortalGodMode",
			"FastEat", "AutoReplenish", "NoCooldown", "CooldownBypass", "FastRegen", "AutoMine",
			// World
			// "Scaffold" and "NoClip" are deliberately absent here for the same
			// reason they are absent from the blacklist: "scaffolding" is a vanilla
			// block and "noclip" is generic game-engine vocabulary. Leaving them in
			// this second list meant the Decompile tab kept flagging what the scan
			// tab had already stopped flagging.
			"CivBreak", "LiquidInteract", "Disabler", "ServerCrasher",
			"CoordinateExploit", "PingSpoof", "PacketFly", "BedFucker", "BedBreaker",
			"PacketBypass", "AntiCheatBypass",
			// Fun/Misc
			"Derp", "SpinBot", "Headless", "LoudOut", "ChatSpammer", "AutoAd", "AntiAFK", "AutoSign", "BedAura",
			// Client names
			"Vape V4", "Vape Lite", "Vape", "Raven B+", "Raven Bow", "Raven Weed", "Drip Lite", "Karma",
			"Wurst", "LiquidBounce", "Aristois", "Meteor Client", "Future Client", "Rise Client",
			"FDP Client", "Astolfo", "Novoline", "Sigma", "Huzuni", "Nodus", "Kinky", "WeepCraft",
			"Resilience", "Kami Blue", "ForgeHax", "Phobos", "SalHack", "Kami", "Rusherhack",
			// "Wolfram" removed: it is a mainstream scientific-software name long
			// before it is a Minecraft client.
			"BleachHack", "WWE Client", "Itami", "Whiteout",
			"Tenacity", "Exhibition", "Lime Client",
			"Summer Client", "LiquidBounce+",
			"Doomsday", "DoomsdayClient", "Inertia", "Akrien", "Smoke", "Reaper", "Rise",
			"Impact Client", "Impact", "Baritone", "ImpactClient",
			"Doombubble", "DoomsdayClient",
			"Skidded", "Skid", "Backdoored", "Backdoor",
			"Jello", "Jigsaw", "Ypsilon", "Atrocity",
			"NightX", "NightXClient", "Polar", "Radioactive", "Spectre",
			"SunClient", "SunHack", "ThunderHack", "ThunderhackPlus",
			"Zephyr", "Zerotwo", "ZeroTwo",
			// Additional client names
			"Cumfest", "Fanatic", "FanaticClient", "HackedClient", "HackClient",
			"MouseTweaks", "MoonClient", "PolarClient", "SpectreClient", "SunHack",
			"ZephyrClient", "ZeroTwoClient", "AtrocityClient",
			"VapeCloud", "VapeCloudV4", "VapeV4", "VapeLite",
			"RavenB+", "RavenB3", "RavenB4", "RavenXE",
			"DripLite", "KarmaClient", "RiseHack",
			// Cheat-specific terms (low false positive)
			"HackedClient", "CheatClient", "ModuleManager", "HackManager",
			"ClickGUI", "ClickGui", "HackMenu", "CheatMenu",
			"ToggleHack", "EnableHack", "DisableHack",
			"ReachBypass", "HitBypass", "CombatBypass",
			"NoCheat", "AntiAntiCheat", "BypassAC",
			"SilentCancel", "PacketCancel", "CancelPacket",
			"FakeHit", "SilentHit", "GhostHit",
			"HitBoxExpand", "HitBoxHack", "RangeHack",
			"AutoBlock", "SilentBlock", "BlockReach",
			"TickAccel", "TickAccelerate", "TickExploit",
			"ReachExtension", "AttackExtension",
			"NoSlowDown", "NoSlowPacket",
			"SprintHack", "SprintBypass",
			"HitDelayBypass", "NoAttackDelay",
			"ReachHackBypass", "ReachExtension"
	));

	public static final Set<String> DEFAULT_CHEAT_KEYWORDS = java.util.Collections.unmodifiableSet(new HashSet<>(CHEAT_KEYWORDS));

	private static final Set<String> HIGH_RISK_KEYWORDS = new HashSet<>(Arrays.asList(
			"KillAura", "Killaura", "killaura", "Forcefield", "MultiAura", "Aimbot", "AimAssist", "Triggerbot",
			"AutoClicker", "X-Ray", "XRay", "Tracers", "Chams", "Freecam", "Wallhack",
			"Vape", "Vape V4", "Vape Lite", "Wurst", "LiquidBounce", "Meteor Client", "Future Client",
			"Rise Client", "FDP Client", "Novoline", "Astolfo", "Sigma", "Huzuni", "Nodus", "Phobos",
			"Rusherhack", "ServerCrasher", "Disabler", "PacketFly", "VClip", "HClip",
			"ChestStealer", "AutoTotem", "BHop", "BunnyHop",
			"Doomsday", "DoomsdayClient", "Impact", "Impact Client", "Baritone",
			"Inertia", "Jigsaw", "NightX",
			// New high-risk
			"ReachHack", "ReachBypass", "HitBypass", "CombatBypass",
			"FakeLag", "TickShift", "BackTrack", "ComboAura", "SilentAura",
			"PacketBypass", "AntiCheatBypass", "BypassAC", "AntiAntiCheat",
			"NoCheat", "HackedClient", "CheatClient",
			"FlyHack", "SpeedHack", "TimerHack",
			"ReachExtension", "AttackExtension", "RangeHack",
			"HitBoxExpand", "HitBoxHack", "TickExploit",
			"NoHurtCam", "CameraClip",
			"NoCooldown", "CooldownBypass",
			"BedBreaker", "AutoMine",
			"Cumfest", "Fanatic", "FanaticClient",
			"VapeCloud", "VapeV4", "VapeLite",
			"RavenB+", "RavenB3", "RavenB4", "RavenXE",
			"DripLite", "MoonClient",
			"SilentCancel", "PacketCancel",
			"FakeHit", "SilentHit", "GhostHit",
			"TickAccel", "TickAccelerate",
			"ClickAimAssist", "ClickCrystal", "AutoCrystal", "CrystalAura",
			"AnchorAura", "BedAura", "SurroundBreak", "BurrowBreak"
	));

	private static final Set<String> LOW_RISK_KEYWORDS = new HashSet<>(Arrays.asList(
			"Nametags", "AntiAFK", "AutoSign",
			"Derp", "SpinBot", "Headless", "LoudOut", "ChatSpammer", "AutoAd",
			"AutoDisconnect", "AutoLeave", "AutoLog", "AutoReconnect", "AutoRespawn",
			"AutoShear", "AutoReplenish",
			"PlayerFinder", "Nameprotect",
			"ModuleManager", "HackManager", "ClickGUI", "ClickGui",
			"SneakSpeed", "AirJump", "NoJumpDelay",
			"FastRegen",
			"SprintHack", "SprintBypass",
			"ToggleHack", "EnableHack", "DisableHack",
			"NoSlowDown", "NoSlowPacket",
			"AutoBlock", "SilentBlock",
			"HitDelayBypass", "NoAttackDelay",
			"ReachHackBypass",
			"Triggerbot", "triggerbot", "AimAssist", "aimassist",
			"ClickAimAssist", "clickaimassist",
			"ClickCrystal", "clickcrystal", "CrystalAura",
			"AutoCrystal", "CrystalFinder",
			"SurroundBreak", "BurrowBreak",
			"AnchorAura", "BedAura"
	));

	public static RiskLevel getRiskLevel(String keyword) {
		if (HIGH_RISK_KEYWORDS.contains(keyword)) return RiskLevel.HIGH;
		if (LOW_RISK_KEYWORDS.contains(keyword)) return RiskLevel.LOW;
		return RiskLevel.MEDIUM;
	}

	public static List<DetectionResult> scanJar(String jarPath, JarFile jfile,
			DecompilerConfig decompilerConfig,
			Set<String> customKeywords, Set<String> excludeKeywords, boolean runCFR) {

		ensureCompiledPattern(customKeywords);
		final java.util.regex.Pattern keywordPattern = cachedKeywordPattern;
		final java.util.Map<String, String> finalLowerToOriginal = cachedLowerToOriginal;

		List<DetectionResult> results = new ArrayList<>();

		// Check the JAR filename itself for client names
		String jarName = new File(jarPath).getName();
		java.util.regex.Matcher jarMatcher = keywordPattern.matcher(jarName);
		while (jarMatcher.find()) {
			String matched = jarMatcher.group().toLowerCase();
			String keyword = finalLowerToOriginal.get(matched);
			if (keyword != null) {
				RiskLevel risk = getRiskLevel(keyword);
				results.add(new DetectionResult(jarPath, "(jar filename)", 0, keyword,
						"JAR file name contains: " + jarName, risk));
			}
		}

		// Single-pass: read each class entry and scan for keywords only (no bytecode analysis)
		java.util.Enumeration<JarEntry> entries = jfile.entries();
		byte[] buf = new byte[16384];
		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			String entryName = entry.getName();
			if (!entryName.endsWith(".class")) continue;
			if (entryName.contains("$") && decompilerConfig.getExcludeNestedTypes()) continue;

			try (java.io.InputStream is = jfile.getInputStream(entry)) {
				java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(
						(int) Math.min(entry.getSize(), 65536));
				int len;
				while ((len = is.read(buf)) != -1) {
					baos.write(buf, 0, len);
				}
				byte[] classBytes = baos.toByteArray();
				String className = entryName.replace(".class", "").replace("/", ".");

				// Raw byte keyword scanning only
				String classString = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
				java.util.regex.Matcher rawMatcher = keywordPattern.matcher(classString);
				while (rawMatcher.find()) {
					String matched = rawMatcher.group();
					String keyword = finalLowerToOriginal.get(matched.toLowerCase());
					if (keyword == null) keyword = matched;
					RiskLevel risk = getRiskLevel(keyword);
					int idx = rawMatcher.start();

					// Extract readable string around the keyword match
					// Scan backwards for start of readable sequence
					int strStart = idx;
					while (strStart > 0) {
						char ch = classString.charAt(strStart - 1);
						if (ch < 32 || ch > 126) break;
						strStart--;
					}
					// Scan forward for end of readable sequence
					int strEnd = rawMatcher.end();
					while (strEnd < classString.length()) {
						char ch = classString.charAt(strEnd);
						if (ch < 32 || ch > 126) break;
						strEnd++;
					}
					String lineContent = classString.substring(strStart, strEnd);
					// Limit length
					if (lineContent.length() > 200) {
						// Try to show keyword centered
						int kwStart = idx - strStart;
						int kwLen = rawMatcher.end() - idx;
						int contextStart = Math.max(0, kwStart - 80);
						int contextEnd = Math.min(lineContent.length(), kwStart + kwLen + 80);
						lineContent = lineContent.substring(contextStart, contextEnd);
						if (contextStart > 0) lineContent = "..." + lineContent;
						if (contextEnd < (strEnd - strStart)) lineContent = lineContent + "...";
					}
					lineContent = lineContent.trim();
					if (lineContent.isEmpty()) {
						lineContent = keyword;
					}

					results.add(new DetectionResult(jarPath, className, 0, keyword,
							lineContent, risk));
				}
			} catch (Throwable t) {
			}
		}

		// CFR decompile + keyword scan — only when runCFR is true (obfuscated or suspicious JARs)
		if (runCFR) {
			try {
				results.addAll(scanWithCFRDecompile(jarPath, jfile, keywordPattern, finalLowerToOriginal, decompilerConfig));
			} catch (Throwable t) {
			}
		}

		return results;
	}

	private static List<DetectionResult> scanWithCFRDecompile(String jarPath, JarFile jfile,
			java.util.regex.Pattern keywordPattern, java.util.Map<String, String> lowerToOriginal,
			DecompilerConfig decompilerConfig) {

		List<DetectionResult> results = new ArrayList<>();
		java.util.Enumeration<JarEntry> entries = jfile.entries();
		int maxClassesToDecompile = 200;
		int decompiledCount = 0;

		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			String entryName = entry.getName();
			if (!entryName.endsWith(".class")) continue;
			if (entryName.contains("$") && decompilerConfig.getExcludeNestedTypes()) continue;

			// Skip standard library packages
			boolean skip = false;
			String[] skipPrefixes = {"java/", "javax/", "sun/", "com/sun/", "org/apache/", "com/google/",
					"com/mojang/", "net/minecraft/", "org/objectweb/", "org/jetbrains/", "com/intellij/",
					"org/junit/", "kotlin/", "kotlinx/", "org/slf4j/", "org/eclipse/", "org/gradle/",
					"com/github/", "javassist/", "net/sf/", "org/bouncycastle/"};
			for (String prefix : skipPrefixes) {
				if (entryName.startsWith(prefix)) { skip = true; break; }
			}
			if (skip) continue;

			if (decompiledCount >= maxClassesToDecompile) break;

			try {
				String className = entryName.replace(".class", "").replace("/", ".");
				String decompiled = CfrDecompiler.decompileFromJar(jfile, entryName, decompilerConfig);
				if (decompiled == null || decompiled.isEmpty()) continue;
				decompiledCount++;

				java.util.regex.Matcher m = keywordPattern.matcher(decompiled);
				while (m.find()) {
					String matched = m.group();
					String keyword = lowerToOriginal.get(matched.toLowerCase());
					if (keyword == null) keyword = matched;
					RiskLevel risk = getRiskLevel(keyword);

					// Extract context line
					int lineStart = m.start();
					int lineEnd = m.end();
					while (lineStart > 0 && decompiled.charAt(lineStart - 1) != '\n') lineStart--;
					while (lineEnd < decompiled.length() && decompiled.charAt(lineEnd) != '\n') lineEnd++;
					String lineContent = decompiled.substring(lineStart, lineEnd).trim();
					if (lineContent.length() > 200) {
						lineContent = lineContent.substring(0, 200) + "...";
					}

					results.add(new DetectionResult(jarPath, className, 0, keyword, lineContent, risk));
				}
			} catch (Throwable t) {
			}
		}

		return results;
	}

}
