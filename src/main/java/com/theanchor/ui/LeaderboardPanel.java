package com.theanchor.ui;

import com.theanchor.model.AnchorModels;
import com.theanchor.service.AnchorDataService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;

/** Public clan standings; connection credentials never enter this view. */
final class LeaderboardPanel extends JPanel {
	private static final Color GOLD = AnchorTheme.GOLD;
	private final AnchorDataService data;
	private final JComboBox<String> metric = new JComboBox<>(new String[]{"PvM Points", "Clan Points", "Clan Rank"});
	private final JTextField search = new JTextField();
	private final JPanel rows = new JPanel();
	private final JLabel summary = new JLabel();
	private final JLabel updated = new JLabel();

	LeaderboardPanel(AnchorDataService data) {
		this.data = data;
		AnchorTheme.styleComboBox(metric);
		setLayout(new BorderLayout(0, 10));
		setBackground(AnchorTheme.BACKGROUND);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setOpaque(false);
		JLabel title = new JLabel("LEADERBOARDS");
		title.setFont(AnchorTheme.font(Font.BOLD, 16f));
		title.setForeground(GOLD);
		controls.add(title);
		controls.add(Box.createVerticalStrut(10));
		metric.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		metric.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel selection = new JPanel(new BorderLayout(6, 0));
		selection.setOpaque(false);
		selection.setAlignmentX(Component.LEFT_ALIGNMENT);
		selection.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		selection.add(metric, BorderLayout.CENTER);
		JButton refresh = AnchorPanel.actionButton("Refresh", false);
		refresh.setPreferredSize(new Dimension(52, 26));
		refresh.addActionListener(e -> data.refreshLeaderboards());
		selection.add(refresh, BorderLayout.EAST);
		controls.add(selection);
		controls.add(Box.createVerticalStrut(6));
		search.setBackground(AnchorTheme.SURFACE);
		search.setForeground(AnchorTheme.TEXT);
		search.setCaretColor(AnchorTheme.GOLD);
		search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(AnchorTheme.BORDER), BorderFactory.createEmptyBorder(5, 7, 5, 7)));
		search.setToolTipText("Search member names");
		search.getAccessibleContext().setAccessibleName("Search member names");
		JLabel searchLabel = new JLabel("Find a member");
		searchLabel.setForeground(Color.LIGHT_GRAY);
		controls.add(searchLabel);
		controls.add(Box.createVerticalStrut(2));
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 27));
		search.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.add(search);
		controls.add(Box.createVerticalStrut(8));

		summary.setForeground(Color.LIGHT_GRAY);
		controls.add(summary);
		updated.setForeground(new Color(150, 154, 159));
		updated.setFont(updated.getFont().deriveFont(10f));
		controls.add(updated);
		add(controls, BorderLayout.NORTH);
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setOpaque(false);
		add(rows, BorderLayout.CENTER);
		metric.addActionListener(e -> refresh());
		search.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent e) { refresh(); }
			public void removeUpdate(DocumentEvent e) { refresh(); }
			public void changedUpdate(DocumentEvent e) { refresh(); }
		});
		refresh();
	}

	void refresh() {
		rows.removeAll();
		AnchorModels.Leaderboards board = data.leaderboards();
		String message = data.leaderboardMessage();
		updated.setText("");
		if (board == null) {
			summary.setText("Clan standings");
			addNotice(message);
		} else {
			List<AnchorModels.LeaderboardMember> members = new ArrayList<>();
			String query = search.getText().trim().toLowerCase(Locale.ROOT);
			int mode = metric.getSelectedIndex();
			for (AnchorModels.LeaderboardMember member : board.members == null ? java.util.Collections.<AnchorModels.LeaderboardMember>emptyList() : board.members) {
				if (member != null && member.name != null && member.name.toLowerCase(Locale.ROOT).contains(query)) members.add(member);
			}
			Comparator<AnchorModels.LeaderboardMember> order = mode == 2
					? Comparator.comparingInt((AnchorModels.LeaderboardMember m) -> m.rankOrder).reversed()
					: Comparator.comparingInt(m -> position(mode == 0 ? m.pvmRank : m.clanRank));
			members.sort(order.thenComparing(m -> m.name, String.CASE_INSENSITIVE_ORDER));
			summary.setText(members.size() + " members" + (query.isEmpty() ? "" : " matching search"));
			if (board.updatedAt != null) {
				try {
					java.time.Instant instant = java.time.Instant.parse(board.updatedAt);
					updated.setText("Sheet cache · " + java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm z")
							.withZone(java.time.ZoneId.systemDefault()).format(instant));
				} catch (java.time.format.DateTimeParseException ignored) { updated.setText("Sheet cache timestamp unavailable"); }
			}
			if (message != null && !message.isEmpty()) addNotice(message);
			if (members.isEmpty()) addNotice("No members found.");
			AnchorModels.Profile profile = data.profile();
			String player = profile == null || profile.member == null ? "" : profile.member.name;
			for (AnchorModels.LeaderboardMember member : members) {
				boolean own = normalize(member.name).equals(normalize(player));
				JPanel row = AnchorTheme.surface(own ? GOLD : null);
				row.setLayout(new BorderLayout(7, 0));
				row.setBackground(own ? new Color(57, 51, 37) : AnchorTheme.SURFACE);
				row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
				Integer place = mode == 0 ? member.pvmRank : member.clanRank;
				if (mode != 2) {
					JLabel rank = new JLabel(place == null ? "—" : "#" + place);
					rank.setPreferredSize(new Dimension(34, 22));
					rank.setForeground(place != null && place <= 3 ? GOLD : Color.LIGHT_GRAY);
					row.add(rank, BorderLayout.WEST);
				}
				JPanel identity = new JPanel(new BorderLayout(0, 2));
				identity.setOpaque(false);
				JLabel name = new JLabel();
				name.putClientProperty("html.disable", Boolean.TRUE);
				name.setText(member.name);
				name.setToolTipText(member.name);
				name.setForeground(own ? GOLD : Color.WHITE);
				name.setFont(AnchorTheme.font(Font.BOLD, 12f));
				identity.add(name, BorderLayout.NORTH);
				JLabel role = new JLabel();
				role.putClientProperty("html.disable", Boolean.TRUE);
				role.setText(member.currentRank == null ? "Rank unavailable" : member.currentRank);
				role.setForeground(new Color(155, 160, 165));
				role.setFont(role.getFont().deriveFont(10f));
				identity.add(role, BorderLayout.SOUTH);
				row.add(identity, BorderLayout.CENTER);
				if (mode != 2) {
					JLabel points = new JLabel(java.text.NumberFormat.getIntegerInstance().format(mode == 0 ? member.pvmPoints : member.clanPoints), SwingConstants.RIGHT);
					points.setForeground(GOLD);
					points.setFont(AnchorTheme.font(Font.BOLD, 13f));
					row.add(points, BorderLayout.EAST);
				}
				rows.add(row);
				rows.add(Box.createVerticalStrut(2));
			}
		}
		AnchorTheme.typography(this);
		revalidate();
		repaint();
	}

	private void addNotice(String message) {
		JLabel label = new JLabel("<html>" + escape(message) + "</html>");
		label.setForeground(Color.LIGHT_GRAY);
		rows.add(label);
	}
	private static int position(Integer rank) { return rank == null || rank <= 0 ? Integer.MAX_VALUE : rank; }
	private static String normalize(String name) { return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[ _-]", ""); }
	private static String escape(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
