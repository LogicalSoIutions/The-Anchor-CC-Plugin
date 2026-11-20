package com.theanchor.ui;

import com.theanchor.model.AnchorModels;
import com.theanchor.service.AnchorDataService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/** On-demand public member card. It never changes the logged-in player's profile. */
final class MemberProfilePanel extends JPanel {
	private final AnchorDataService data;
	private final JPanel content = section();
	private final JPanel diaryDetails = section();
	private long requestGeneration;

	MemberProfilePanel(AnchorDataService data, Runnable back) {
		this.data = data;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		JButton backButton = AnchorPanel.actionButton("‹  Leaderboards", false);
		backButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		backButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		backButton.addActionListener(e -> { requestGeneration++; back.run(); });
		add(backButton);
		add(Box.createVerticalStrut(8));
		add(content);
		add(diaryDetails);
	}

	void open(AnchorModels.LeaderboardMember selected) {
		long generation = ++requestGeneration;
		content.removeAll();
		closeDiary();
		addIdentity(selected);
		List<String> names = Arrays.stream(selected.name.split("[/|]"))
			.map(String::trim).filter(name -> !name.isEmpty()).distinct().collect(Collectors.toList());
		JPanel diary = section();
		content.add(diary);
		loadDiary(diary, names, generation, selected.name);
		for (String name : names) {
			content.add(Box.createVerticalStrut(6));
			JPanel account = section();
			content.add(account);
			if (names.size() > 1) {
				account.add(text(name, true, AnchorTheme.TEXT));
				account.add(Box.createVerticalStrut(6));
			}
			JPanel progress = section();
			account.add(progress);
			loadProgress(progress, name, generation);
		}
		finishLayout();
	}

	private void loadProgress(JPanel progress, String name, long generation) {
		progress.removeAll();
		progress.add(messageCard("Loading player progress…"));
		data.loadMemberProgress(name, result -> SwingUtilities.invokeLater(() -> {
			if (generation != requestGeneration) return;
			progress.removeAll();
			if (!result.isSuccessful() || result.value == null) {
				progress.add(retryCard("Unable to load player progress.", () -> {
					loadProgress(progress, name, generation);
					finishLayout();
				}));
			} else {
				progress.add(combatCard(result.value.combatAchievements));
				progress.add(Box.createVerticalStrut(6));
				progress.add(collectionCard(result.value.collectionLog));
			}
			finishLayout();
		}));
	}

	private void loadDiary(JPanel diary, List<String> names, long generation, String memberName) {
		diary.removeAll();
		diary.add(messageCard("Loading PvM Diary…"));
		data.loadMemberDiary(names, result -> SwingUtilities.invokeLater(() -> {
			if (generation != requestGeneration) return;
			diary.removeAll();
			if (result.isSuccessful() && result.value != null) {
				diary.add(diaryCard(result.value, () -> showDiary(memberName, result.value)));
			} else if (result.statusCode == 404) {
				diary.add(messageCard("No linked PvM Diary profile."));
			} else {
				diary.add(retryCard("Unable to load PvM Diary.", () -> {
					loadDiary(diary, names, generation, memberName);
					finishLayout();
				}));
			}
			finishLayout();
		}));
	}

	private static JPanel section() {
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		return section;
	}

	private static JPanel messageCard(String message) {
		JPanel card = AnchorTheme.surface();
		card.add(text(message, false, AnchorTheme.MUTED));
		return card;
	}

	private static JPanel retryCard(String message, Runnable retryAction) {
		JPanel card = messageCard(message);
		card.add(Box.createVerticalStrut(8));
		JButton retry = AnchorPanel.actionButton("Retry", false);
		retry.setAlignmentX(Component.LEFT_ALIGNMENT);
		retry.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		retry.addActionListener(e -> retryAction.run());
		card.add(retry);
		return card;
	}

	private static JPanel diaryCard(AnchorModels.PvmDiaryStatus diary, Runnable open) {
		JPanel card = AnchorTheme.surface();
		card.add(text("PvM Diary", true, AnchorTheme.TEXT));
		card.add(Box.createVerticalStrut(8));
		card.add(pair("Points", number(diary.earnedPoints) + " / " + number(diary.totalPoints), AnchorTheme.GOLD));
		card.add(pair("Tiers", fraction(diary.completedTiers, diary.totalTiers), AnchorTheme.TEXT));
		if (diary.totalPoints > 0) {
			card.add(Box.createVerticalStrut(8));
			card.add(AnchorTheme.bar(diary.earnedPoints, diary.totalPoints, AnchorTheme.GOLD));
		}
		card.add(Box.createVerticalStrut(8));
		card.add(diaryButton("View PvM Diary", open));
		return card;
	}

	private void showDiary(String memberName, AnchorModels.PvmDiaryStatus diary) {
		content.setVisible(false);
		diaryDetails.setVisible(true);
		diaryDetails.removeAll();
		diaryDetails.add(diaryButton("‹  Member profile", () -> {
			closeDiary();
			finishDiaryNavigation();
		}));
		diaryDetails.add(Box.createVerticalStrut(8));
		diaryDetails.add(text(memberName, true, AnchorTheme.TEXT));
		diaryDetails.add(Box.createVerticalStrut(8));
		AnchorPanel.addDiaryCategories(diaryDetails, diary, category -> {
			diaryDetails.removeAll();
			diaryDetails.add(diaryButton("‹  All activities", () -> showDiary(memberName, diary)));
			diaryDetails.add(Box.createVerticalStrut(8));
			AnchorPanel.addDiaryCategoryDetails(diaryDetails, diary, category, false);
			finishDiaryNavigation();
		});
		finishDiaryNavigation();
	}

	private void closeDiary() {
		diaryDetails.removeAll();
		diaryDetails.setVisible(false);
		content.setVisible(true);
	}

	private void finishDiaryNavigation() {
		finishLayout();
		JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
		if (scroll != null) scroll.getVerticalScrollBar().setValue(0);
	}

	private static JButton diaryButton(String label, Runnable action) {
		JButton button = AnchorPanel.actionButton(label, false);
		button.setAlignmentX(Component.LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		button.addActionListener(e -> action.run());
		return button;
	}

	private void addIdentity(AnchorModels.LeaderboardMember member) {
		JPanel identity = AnchorTheme.surface();
		identity.add(text(member.name, true, AnchorTheme.TEXT));
		identity.add(text(member.currentRank == null ? "Rank unavailable" : member.currentRank, false, AnchorTheme.GOLD));
		identity.add(Box.createVerticalStrut(10));
		identity.add(pair("PvM Points", number(member.pvmPoints), AnchorTheme.GOLD));
		identity.add(pair("PvM Position", position(member.pvmRank), AnchorTheme.MUTED));
		identity.add(Box.createVerticalStrut(6));
		identity.add(pair("Clan Points", number(member.clanPoints), AnchorTheme.GREEN));
		identity.add(pair("Clan Position", position(member.clanRank), AnchorTheme.MUTED));
		content.add(identity);
		content.add(Box.createVerticalStrut(6));
	}

	private static JPanel combatCard(AnchorModels.CombatSummary combat) {
		JPanel card = AnchorTheme.surface();
		card.add(text("Combat Achievements", true, AnchorTheme.TEXT));
		card.add(Box.createVerticalStrut(8));
		if (combat == null) {
			card.add(text("Not synced yet", false, AnchorTheme.MUTED));
			return card;
		}
		String tier = combat.currentTier;
		if (tier != null && !tier.isBlank()) tier = Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
		card.add(pair("Tier", tier == null || tier.isBlank() ? "Not reported" : tier, AnchorTheme.GOLD));
		card.add(pair("Tasks", fraction(combat.completedTasks, combat.totalTasks), AnchorTheme.TEXT));
		card.add(pair("Points", fraction(combat.totalPoints, combat.totalPossiblePoints), AnchorTheme.BLUE));
		if (combat.totalPoints != null && combat.totalPossiblePoints != null && combat.totalPossiblePoints > 0) {
			card.add(Box.createVerticalStrut(8));
			card.add(AnchorTheme.bar(combat.totalPoints, combat.totalPossiblePoints, AnchorTheme.BLUE));
		}
		addTimestamp(card, combat.capturedAt);
		return card;
	}

	private static JPanel collectionCard(AnchorModels.CollectionSummary collection) {
		JPanel card = AnchorTheme.surface();
		card.add(text("Collection Log", true, AnchorTheme.TEXT));
		card.add(Box.createVerticalStrut(8));
		if (collection == null) {
			card.add(text("Not synced yet", false, AnchorTheme.MUTED));
			return card;
		}
		card.add(pair("Slots obtained", fraction(collection.obtainedCount, collection.totalCount), AnchorTheme.GOLD));
		if (collection.obtainedCount != null && collection.totalCount != null && collection.totalCount > 0) {
			card.add(Box.createVerticalStrut(8));
			card.add(AnchorTheme.bar(collection.obtainedCount, collection.totalCount, AnchorTheme.GOLD));
		}
		addTimestamp(card, collection.capturedAt);
		return card;
	}

	private static void addTimestamp(JPanel card, String capturedAt) {
		card.add(Box.createVerticalStrut(8));
		String date = "Sync time unavailable";
		if (capturedAt != null) try {
			date = "Synced " + DateTimeFormatter.ofPattern("MMM d, HH:mm z").withZone(ZoneId.systemDefault()).format(Instant.parse(capturedAt));
		} catch (java.time.format.DateTimeParseException ignored) { }
		card.add(text(date, false, AnchorTheme.MUTED));
	}

	private static JPanel pair(String label, String value, Color color) {
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(text(label, false, AnchorTheme.MUTED), BorderLayout.WEST);
		row.add(text(value, false, color), BorderLayout.EAST);
		return row;
	}

	private static JLabel text(String value, boolean bold, Color color) {
		JLabel label = new JLabel();
		label.putClientProperty("html.disable", Boolean.TRUE);
		label.setText(value);
		label.setToolTipText(value);
		label.setFont(AnchorTheme.font(bold ? Font.BOLD : Font.PLAIN, 12));
		label.setForeground(color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private void finishLayout() {
		AnchorTheme.typography(this);
		revalidate();
		repaint();
	}
	private static String number(long value) { return java.text.NumberFormat.getIntegerInstance().format(value); }
	private static String position(Integer value) { return value == null || value <= 0 ? "Unranked" : "#" + value; }
	private static String fraction(Integer current, Integer total) {
		return (current == null ? "—" : number(current)) + " / " + (total == null ? "—" : number(total));
	}
}
