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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** On-demand public member card. It never changes the logged-in player's profile. */
final class MemberProfilePanel extends JPanel {
	private final AnchorDataService data;
	private final JPanel content = new JPanel();
	private AnchorModels.LeaderboardMember member;
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
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(content);
	}

	void open(AnchorModels.LeaderboardMember selected) {
		member = selected;
		long generation = ++requestGeneration;
		content.removeAll();
		addIdentity();
		JPanel loading = AnchorTheme.surface();
		loading.add(text("Loading player progress…", false, AnchorTheme.MUTED));
		content.add(loading);
		finishLayout();
		data.loadMemberProgress(selected.name, result -> SwingUtilities.invokeLater(() -> {
			if (generation != requestGeneration) return;
			content.removeAll();
			addIdentity();
			if (!result.isSuccessful() || result.value == null) {
				JPanel error = AnchorTheme.surface();
				error.add(text("Unable to load player progress.", false, AnchorTheme.MUTED));
				error.add(Box.createVerticalStrut(8));
				JButton retry = AnchorPanel.actionButton("Retry", false);
				retry.setAlignmentX(Component.LEFT_ALIGNMENT);
				retry.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
				retry.addActionListener(e -> open(member));
				error.add(retry);
				content.add(error);
			} else {
				content.add(combatCard(result.value.combatAchievements));
				content.add(Box.createVerticalStrut(6));
				content.add(collectionCard(result.value.collectionLog));
			}
			finishLayout();
		}));
	}

	private void addIdentity() {
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
