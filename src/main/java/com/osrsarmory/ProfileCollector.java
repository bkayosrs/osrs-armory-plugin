package com.osrsarmory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.util.Text;

/**
 * Reads everything shown on a player's Armory page besides the equipped item IDs.
 * All methods must be called on the client thread.
 */
@Singleton
class ProfileCollector
{
	// Achievement diary regions in in-game order, each with its Easy/Medium/Hard/Elite "complete" varbits.
	private static final Object[][] DIARIES = {
		{"Ardougne", VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE},
		{"Desert", VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE},
		{"Falador", VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE},
		{"Fremennik", VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE},
		{"Kandarin", VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE},
		{"Karamja", VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE},
		{"Kourend & Kebos", VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE},
		{"Lumbridge & Draynor", VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE},
		{"Morytania", VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE},
		{"Varrock", VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE},
		{"Western Provinces", VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE},
		{"Wilderness", VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE},
	};

	// Combat achievement completion is stored as bit flags spread over these varps.
	private static final int[] CA_TASK_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20,
	};

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	// Real (unboosted) level and XP per skill, or null if stats haven't loaded yet.
	Map<String, Map<String, Integer>> skills()
	{
		// Every account has at least 1154 Hitpoints XP, so 0 means stats aren't loaded.
		if (client.getSkillExperience(Skill.HITPOINTS) <= 0)
		{
			return null;
		}

		Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			if (skill.name().equals("OVERALL"))
			{
				continue; // deprecated pseudo-skill in older RuneLite versions
			}
			Map<String, Integer> stat = new LinkedHashMap<>();
			stat.put("level", client.getRealSkillLevel(skill));
			stat.put("xp", client.getSkillExperience(skill));
			result.put(skill.name(), stat);
		}
		return result;
	}

	// In-game names of the worn items, keyed by item ID, so the website needs no item database.
	Map<String, String> itemNames(Map<String, Integer> equipment)
	{
		Map<String, String> result = new LinkedHashMap<>();
		for (int id : equipment.values())
		{
			if (id >= 0)
			{
				result.put(String.valueOf(id), itemManager.getItemComposition(id).getName());
			}
		}
		return result;
	}

	// Summed equipment bonuses, like the in-game Equipment Stats screen.
	Map<String, Number> bonuses(Map<String, Integer> equipment)
	{
		int astab = 0, aslash = 0, acrush = 0, amagic = 0, arange = 0;
		int dstab = 0, dslash = 0, dcrush = 0, dmagic = 0, drange = 0;
		int str = 0, rstr = 0, prayer = 0, aspeed = 0;
		float mdmg = 0;
		double weight = 0;

		for (Map.Entry<String, Integer> slot : equipment.entrySet())
		{
			int id = slot.getValue();
			if (id < 0)
			{
				continue;
			}
			ItemStats stats = itemManager.getItemStats(id);
			if (stats == null)
			{
				continue;
			}
			weight += stats.getWeight();
			ItemEquipmentStats e = stats.getEquipment();
			if (e == null)
			{
				continue;
			}
			astab += e.getAstab();
			aslash += e.getAslash();
			acrush += e.getAcrush();
			amagic += e.getAmagic();
			arange += e.getArange();
			dstab += e.getDstab();
			dslash += e.getDslash();
			dcrush += e.getDcrush();
			dmagic += e.getDmagic();
			drange += e.getDrange();
			str += e.getStr();
			rstr += e.getRstr();
			mdmg += e.getMdmg();
			prayer += e.getPrayer();
			if (slot.getKey().equals("WEAPON"))
			{
				aspeed = e.getAspeed();
			}
		}

		Map<String, Number> result = new LinkedHashMap<>();
		result.put("astab", astab);
		result.put("aslash", aslash);
		result.put("acrush", acrush);
		result.put("amagic", amagic);
		result.put("arange", arange);
		result.put("dstab", dstab);
		result.put("dslash", dslash);
		result.put("dcrush", dcrush);
		result.put("dmagic", dmagic);
		result.put("drange", drange);
		result.put("str", str);
		result.put("rstr", rstr);
		result.put("mdmg", Math.round(mdmg * 10) / 10.0);
		result.put("prayer", prayer);
		// Unarmed attacks every 4 ticks.
		result.put("aspeed", aspeed > 0 ? aspeed : 4);
		result.put("weight", Math.round(weight * 10) / 10.0);
		return result;
	}

	// Everything slower-changing: quests, diaries, combat tasks and the side-panel summaries.
	Map<String, Object> profile()
	{
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("questPoints", client.getVarpValue(VarPlayerID.QP));
		result.put("quests", quests());
		result.put("diaries", diaries());
		result.put("combatTasksCompleted", combatTasksCompleted());
		// The two below are only available while their side panel is loaded; null means "unknown",
		// and the server keeps the last known value.
		result.put("summary", summary());
		result.put("adventurePath", adventurePath());
		return result;
	}

	private Map<String, String> quests()
	{
		Map<String, String> result = new LinkedHashMap<>();
		for (Quest quest : Quest.values())
		{
			QuestState state = quest.getState(client);
			result.put(quest.getName(), state.name());
		}
		return result;
	}

	private Map<String, boolean[]> diaries()
	{
		Map<String, boolean[]> result = new LinkedHashMap<>();
		for (Object[] diary : DIARIES)
		{
			boolean[] tiers = new boolean[4];
			for (int i = 0; i < 4; i++)
			{
				tiers[i] = client.getVarbitValue((int) diary[i + 1]) > 0;
			}
			result.put((String) diary[0], tiers);
		}
		return result;
	}

	private int combatTasksCompleted()
	{
		int count = 0;
		for (int varp : CA_TASK_VARPS)
		{
			count += Integer.bitCount(client.getVarpValue(varp));
		}
		return count;
	}

	// The Character Summary tab ("Quests Completed: 35/184", ...) as label -> value pairs.
	private Map<String, String> summary()
	{
		Widget contents = client.getWidget(InterfaceID.AccountSummarySidepanel.SUMMARY_CONTENTS);
		if (contents == null)
		{
			return null;
		}

		List<String> texts = new ArrayList<>();
		collectTexts(contents, texts);

		// Labels end with ':' and are followed by their value, or hold "Label: value" in one widget.
		Map<String, String> result = new LinkedHashMap<>();
		for (int i = 0; i < texts.size(); i++)
		{
			String text = texts.get(i);
			if (text.endsWith(":") && i + 1 < texts.size() && !texts.get(i + 1).endsWith(":"))
			{
				result.put(text.substring(0, text.length() - 1).trim(), texts.get(++i));
			}
			else if (text.contains(": "))
			{
				int split = text.indexOf(": ");
				result.put(text.substring(0, split).trim(), text.substring(split + 2).trim());
			}
		}
		result.values().removeIf(v -> v.toLowerCase().contains("click to reveal"));
		return result.isEmpty() ? null : result;
	}

	// The Adventure Paths side panel: the selected path, its current task and progress.
	private Map<String, String> adventurePath()
	{
		Widget pathName = client.getWidget(InterfaceID.AdventurepathSide.PATH_NAME);
		if (pathName == null || pathName.isHidden() || clean(pathName.getText()).isEmpty())
		{
			return null;
		}

		Map<String, String> result = new LinkedHashMap<>();
		result.put("path", clean(pathName.getText()));
		result.put("task", widgetText(InterfaceID.AdventurepathSide.TASK_NAME));
		result.put("description", widgetText(InterfaceID.AdventurepathSide.TASK_DESCRIPTION));

		List<String> progress = new ArrayList<>();
		Widget bar = client.getWidget(InterfaceID.AdventurepathSide.PROGRESS_BAR);
		if (bar != null)
		{
			collectTexts(bar, progress);
		}
		result.put("progress", progress.isEmpty() ? "" : progress.get(0));
		return result;
	}

	private String widgetText(int componentId)
	{
		Widget widget = client.getWidget(componentId);
		return widget == null ? "" : clean(widget.getText());
	}

	private static void collectTexts(Widget widget, List<String> out)
	{
		if (widget == null || widget.isHidden())
		{
			return;
		}
		String text = clean(widget.getText());
		if (!text.isEmpty())
		{
			out.add(text);
		}
		collectTexts(widget.getStaticChildren(), out);
		collectTexts(widget.getDynamicChildren(), out);
		collectTexts(widget.getNestedChildren(), out);
	}

	private static void collectTexts(Widget[] widgets, List<String> out)
	{
		if (widgets == null)
		{
			return;
		}
		for (Widget child : widgets)
		{
			collectTexts(child, out);
		}
	}

	private static String clean(String text)
	{
		if (text == null)
		{
			return "";
		}
		return Text.removeTags(text.replace("<br>", " "))
			.replace((char) 0xA0, ' ')
			.replaceAll("\\s+", " ")
			.trim();
	}
}
