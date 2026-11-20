/*
 * Portions of this panel are adapted from the PB Tracker Sync, Llama Club, and
 * Terpinheimer plugin panels. Visual components also adapt Reval Clan; see
 * LICENSES/reval-clan-LICENSE.txt. See LICENSES/pb-tracker-sync-LICENSE.txt,
 * LICENSES/llama-LICENSE.txt, and LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.ui;

import com.theanchor.AnchorConfig;
import com.theanchor.evidence.EventPipeline;
import com.theanchor.evidence.EvidenceStore;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.AnchorDataService;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

public class AnchorPanel extends PluginPanel {
	private static final Color GOLD = AnchorTheme.GOLD;
	private static final Color TAB_BG = new Color(30, 33, 36);
	private static final Color TAB_ACTIVE_FG = new Color(20, 20, 20);
	private static final Color TAB_INACTIVE_FG = new Color(170, 170, 170);
	private static final Color TAB_HOVER_BG = new Color(55, 60, 65);
	private static final Color CONNECTED_COLOR = new Color(76, 175, 80);
	private static final Color DISCONNECTED_COLOR = new Color(158, 158, 158);
	private static final Color BOTW_ACCENT = new Color(176, 70, 67);
	private static final Color SOTW_ACCENT = new Color(55, 142, 157);
	private static final Color PVM_ACCENT = AnchorTheme.BLUE;
	private static final Color CLAN_ACCENT = AnchorTheme.GREEN;
	private static final Color SCORE_COLOR = new Color(238, 205, 124);
	private static final int NAV_BUTTON_HEIGHT = 28;
	private static final Color[] RANK_COLORS = {
			new Color(238, 193, 73), new Color(185, 195, 204), new Color(190, 126, 75)
	};
	private final AnchorDataService data;
	private final EvidenceStore evidence;
	private final EventPipeline pipeline;
	private final AnchorConfig config;
	private final ConfigManager configManager;
	private final SpriteManager spriteManager;
	private final ItemManager itemManager;
	private final Runnable dataListener = this::refreshOnEdt;
	private final AtomicBoolean refreshQueued = new AtomicBoolean();
	private boolean submissionsLoading;
	private boolean submissionsReloadRequested;
	private SubmissionFilter submissionFilter = SubmissionFilter.ALL;
	private final JLabel banner = new AspectRatioIconLabel();
	private final JLabel avatar = new JLabel();
	private final JLabel identity = new JLabel("Log in to RuneLite", SwingConstants.LEFT);
	private final JLabel status = muted("");
	private final JLabel connectionDot = new JLabel();
	private final JPanel home = verticalPanel();
	private final JPanel memberHeader = new JPanel(new BorderLayout(6, 0));
	private String selectedDiaryCategory;
	private final JPanel submissions = verticalPanel();
	private final CardLayout contentCards = new CardLayout();
	private final JPanel contentPanel = new JPanel(contentCards);
	private final JPanel competitionsPage = verticalPanel();
	private final JPanel diaryPage = verticalPanel();
	private final JPanel rankPage = verticalPanel();
	private JScrollPane rankScroll;
	private JPanel currentRankRow;
	private JScrollPane diaryScroll;
	private final LeaderboardPanel leaderboardsPage;
	private final java.util.Map<String, JButton> navigationTabs = new java.util.LinkedHashMap<>();
	private final JPanel settingsPage = verticalPanel();
	private boolean rotmExpanded = true;
	private boolean botwExpanded = true;
	private boolean sotwExpanded = true;
	private String activeTab = "Profile";

	private enum SubmissionFilter {
		ALL("All", null), LOOT("Loot", "loot"), DIARY("Diary", "diary"), PBS("PBs", "personal_best"),
		OTHER("Other", "other");

		private final String label;
		private final String category;

		SubmissionFilter(String label, String category) {
			this.label = label;
			this.category = category;
		}

		@Override public String toString() { return label; }
	}

	@Inject
	public AnchorPanel(AnchorDataService data, EvidenceStore evidence, EventPipeline pipeline,
			AnchorConfig config, ConfigManager configManager, SpriteManager spriteManager, ItemManager itemManager) {
		super(false);
		this.data = data;
		this.evidence = evidence;
		this.pipeline = pipeline;
		this.config = config;
		this.configManager = configManager;
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;
		this.leaderboardsPage = new LeaderboardPanel(data);
		setLayout(new BorderLayout());
		setBackground(AnchorTheme.BACKGROUND);
		setBorder(null);

		JPanel header = new JPanel(new GridBagLayout());
		header.setBackground(AnchorTheme.BACKGROUND);
		GridBagConstraints headerRow = new GridBagConstraints();
		headerRow.gridx = 0;
		headerRow.gridy = 0;
		headerRow.weightx = 1.0;
		headerRow.fill = GridBagConstraints.HORIZONTAL;
		headerRow.anchor = GridBagConstraints.NORTHWEST;
		banner.setAlignmentX(Component.LEFT_ALIGNMENT);
		refreshBanner();
		header.add(banner, headerRow);

		// Profile bar with connection status dot
		JPanel profile = memberHeader;
		profile.setOpaque(false);
		profile.setAlignmentX(Component.LEFT_ALIGNMENT);
		profile.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		avatar.setPreferredSize(new Dimension(32, 32));
		avatar.setIcon(new ImageIcon(scale(placeholderAvatar(), 32, 32)));
		profile.add(avatar, BorderLayout.WEST);

		JPanel text = verticalPanel();
		text.setOpaque(false);
		identity.setForeground(Color.WHITE);
		identity.setFont(AnchorTheme.font(Font.BOLD, 13f));
		text.add(identity);
		text.add(status);
		profile.add(text, BorderLayout.CENTER);

		connectionDot.setPreferredSize(new Dimension(14, 14));
		connectionDot.setToolTipText("Not connected");
		connectionDot.setHorizontalAlignment(SwingConstants.CENTER);
		connectionDot.setVerticalAlignment(SwingConstants.CENTER);
		profile.add(connectionDot, BorderLayout.EAST);


		// Compact navigation keeps each clan feature within easy reach.
		JPanel navigation = fullWidthVerticalPanel();
		navigation.setLayout(new GridBagLayout());
		navigation.setBackground(AnchorTheme.BACKGROUND);
		navigation.setAlignmentX(Component.LEFT_ALIGNMENT);
		GridBagConstraints navigationRow = new GridBagConstraints();
		navigationRow.gridx = 0;
		navigationRow.gridy = 0;
		navigationRow.weightx = 1.0;
		navigationRow.fill = GridBagConstraints.HORIZONTAL;
		navigationRow.anchor = GridBagConstraints.NORTHWEST;
		navigation.add(buildTabBar(), navigationRow);

		headerRow.gridy++;
		header.add(navigation, headerRow);

		// Content area with CardLayout
		contentPanel.setBackground(AnchorTheme.BACKGROUND);
		contentPanel.add(scroll(home, true), "Profile");
		diaryScroll = scroll(diaryPage, true);
		contentPanel.add(diaryScroll, "PvM Diary");
		rankScroll = scroll(rankPage, true);
		contentPanel.add(rankScroll, "Rank Ladder");
		contentPanel.add(scroll(leaderboardsPage, true), "Leaderboards");
		contentPanel.add(scroll(submissions, true), "Submissions");
		buildSettingsPage();
		contentPanel.add(scroll(settingsPage, true), "Settings");
		contentCards.show(contentPanel, activeTab);

		add(header, BorderLayout.NORTH);
		add(contentPanel, BorderLayout.CENTER);
		data.addListener(dataListener);
		pipeline.addListener(dataListener);
		refresh();
	}

	private void buildSettingsPage() {
		settingsPage.add(pageHeading("SETTINGS", ""));
		JPanel actions = card();
		actions.add(label("Profile & uploads", true));
		actions.add(Box.createVerticalStrut(8));
		for (JButton button : new JButton[]{
				configActionButton("Refresh Profile", "refreshProfile", true),
				configActionButton("Retry Uploads", "retryOutbox", false),
				configActionButton("Clear Image Cache", "clearImageCache", false)}) {
			button.setAlignmentX(Component.LEFT_ALIGNMENT);
			button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
			actions.add(button);
			actions.add(Box.createVerticalStrut(6));
		}
		settingsPage.add(actions);

	}

	private JButton configActionButton(String text, String key, boolean primary) {
		JButton button = actionButton(text, primary);
		button.addActionListener(e -> {
			// Always generate a change event, even if an older plugin version left this key
			// set.
			configManager.setConfiguration(AnchorConfig.GROUP, key, false);
			configManager.setConfiguration(AnchorConfig.GROUP, key, true);
		});
		return button;
	}


	private JPanel buildTabBar() {
		JPanel bar = new JPanel(new GridBagLayout());
		bar.setOpaque(false);
		bar.setBorder(BorderFactory.createEmptyBorder(8, 8, 2, 8));
		String[] tabs = {"Profile", "Leaderboards", "PvM Diary", "Submissions", "Settings"};
		for (int i = 0; i < tabs.length; i++) {
			String name = tabs[i];
			JButton tab = actionButton(name, false);
			tab.setPreferredSize(new Dimension(0, 28));
			tab.putClientProperty("navigation", Boolean.TRUE);
			tab.addActionListener(e -> switchTab(name));
			navigationTabs.put(name, tab);
			GridBagConstraints cell = new GridBagConstraints();
			cell.gridx = i % 2;
			cell.gridy = i / 2;
			cell.gridwidth = i == tabs.length - 1 ? 2 : 1;
			cell.weightx = cell.gridwidth;
			cell.fill = GridBagConstraints.HORIZONTAL;
			cell.insets = new Insets(i < 2 ? 0 : 4, 0, 0, i % 2 == 0 && cell.gridwidth == 1 ? 4 : 0);
			bar.add(tab, cell);
		}
		updateNavigation();
		return bar;
	}

	private void updateNavigation() {
		navigationTabs.forEach((name, tab) -> {
			boolean selected = name.equals(activeTab);
			tab.setBackground(selected ? AnchorTheme.BLUE : AnchorTheme.SURFACE);
			tab.setForeground(selected ? Color.WHITE : AnchorTheme.MUTED);
			tab.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
			tab.setBorderPainted(false);
			tab.setSelected(selected);
		});
	}

	private void switchTab(String tabName) {
		activeTab = tabName;
		updateNavigation();
		contentCards.show(contentPanel, tabName);
		if ("Leaderboards".equals(tabName) && data.leaderboards() == null) data.refreshLeaderboards();
	}

	private void refreshOnEdt() {
		if (!refreshQueued.compareAndSet(false, true))
			return;
		SwingUtilities.invokeLater(() -> {
			refreshQueued.set(false);
			refresh();
		});
	}

	public void refreshBanner() {
		Runnable update = () -> {
			banner.setIcon(loadBannerIcon(config.animatedBanner()));
			((AspectRatioIconLabel) banner).refreshAspectRatio();
			banner.revalidate();
			banner.repaint();
		};
		if (SwingUtilities.isEventDispatchThread())
			update.run();
		else
			SwingUtilities.invokeLater(update);
	}

	private void refresh() {
		AnchorModels.Profile profile = data.profile();
		if (profile == null || profile.member == null) {
			identity.setText("The Anchor");
			status.setText(data.message());
			avatar.setIcon(new ImageIcon(scale(placeholderAvatar(), 32, 32)));
		} else {
			String rank = profile.standings == null ? "—" : value(profile.standings.currentRank);
			identity.setText(html("<b>" + esc(profile.member.name) + "</b><br><span style='color:#eecd7c'>"
					+ esc(rank) + "</span>"));
			String memberStatus = value(profile.member.rosterRank);
			if (profile.member.status != null && !profile.member.status.isBlank()) {
				memberStatus += " · " + profile.member.status;
			}
			status.setText(memberStatus);
			avatar.setIcon(new ImageIcon(
					scale(data.profileImage() == null ? placeholderAvatar() : data.profileImage(), 32, 32)));
		}
		updateConnectionDot();
		rebuildHome();
		rebuildSubmissions();
		leaderboardsPage.refresh();
		AnchorTheme.typography(this);
		revalidate();
		repaint();
	}

	private void updateConnectionDot() {
		AnchorDataService.Connection conn = data.connection();
		boolean connected = conn == AnchorDataService.Connection.CONNECTED;
		Color dotColor = connected ? CONNECTED_COLOR : DISCONNECTED_COLOR;
		String tooltip;
		switch (conn) {
			case CONNECTED:
				tooltip = "Connected";
				break;
			case CHECKING:
				tooltip = "Checking code…";
				break;
			case INVALID:
				tooltip = "Invalid code";
				break;
			case UNAVAILABLE:
				tooltip = "Unavailable";
				break;
			default:
				tooltip = "Not connected";
		}
		connectionDot.setToolTipText(tooltip);
		connectionDot.setIcon(new ImageIcon(createDotImage(dotColor)));
	}

	private static BufferedImage createDotImage(Color color) {
		int size = 10;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);
		g.fillOval(0, 0, size, size);
		g.dispose();
		return img;
	}

	private void rebuildHome() {
		home.removeAll();
		competitionsPage.removeAll();
		diaryPage.removeAll();
		home.add(Box.createVerticalStrut(4));
		competitionsPage.add(pageHeading("Competitions", ""));

		AnchorModels.Profile profile = data.profile();
		if (profile != null && profile.standings != null) {
			AnchorModels.Standings s = profile.standings;
			JPanel profileCard = card();
			profileCard.add(memberHeader);
			home.add(profileCard);
			home.add(Box.createVerticalStrut(8));
			home.add(rankSummary(s));
			home.add(Box.createVerticalStrut(6));
		}
		if ("Rank Ladder".equals(activeTab)) rebuildRankLadder(profile == null ? null : profile.standings);
		if (profile == null || profile.standings == null) {
			JPanel empty = card();
			empty.add(wrappedMuted(data.message()));
			home.add(empty);
		}
		JPanel diaryOverview = card();
		diaryOverview.add(label("PvM Diary", true));
		diaryOverview.add(Box.createVerticalStrut(6));
		AnchorModels.PvmDiaryStatus diary = data.pvmDiaryStatus();
		if (diary != null) diaryOverview.add(diarySummary(diary));
		else diaryOverview.add(wrappedMuted(profile == null ? "Log in to view your diary progress" : "Diary progress is currently unavailable"));
		diaryOverview.add(Box.createVerticalStrut(10));
		JButton openDiary = actionButton("View Diary", false);
		openDiary.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		openDiary.setAlignmentX(Component.LEFT_ALIGNMENT);
		openDiary.addActionListener(e -> switchTab("PvM Diary"));
		diaryOverview.add(openDiary);
		home.add(diaryOverview);
		AnchorModels.CompetitionPanels competitions = data.competitionPanels();
		AnchorModels.Standings standings = profile == null ? null : profile.standings;
		String playerName = profile == null || profile.member == null ? null : profile.member.name;
		competitionsPage.add(competition("ROTM", competitions == null ? null : competitions.rotm, null,
				standings == null ? null : standings.rotmRank, playerName, rotmExpanded));
		competitionsPage.add(Box.createVerticalStrut(8));
		String weeklyTiming = sharedCompetitionTiming(competitions);
		if (!weeklyTiming.isEmpty()) {
			competitionsPage.add(weeklyTimer(weeklyTiming));
			competitionsPage.add(Box.createVerticalStrut(8));
		}
		boolean authRequired = requiresCompetitionAuthentication(data.connection());
		competitionsPage.add(competition("BOTW", competitions == null ? null : competitions.botw,
				authRequired ? data.unauthenticatedBotwImage() : data.botwImage(),
				standings == null ? null : standings.botwRank, playerName, botwExpanded));
		competitionsPage.add(Box.createVerticalStrut(8));
		competitionsPage.add(competition("SOTW", competitions == null ? null : competitions.sotw,
				authRequired ? data.unauthenticatedSotwImage() : data.sotwImage(),
				standings == null ? null : standings.sotwRank, playerName, sotwExpanded));
		competitionsPage.add(Box.createVerticalStrut(8));
		home.add(Box.createVerticalStrut(6));
		home.add(competitionsPage);
		rebuildDiaryPage();
		competitionsPage.revalidate();
		competitionsPage.repaint();
		diaryPage.revalidate();
		diaryPage.repaint();
	}

	private JPanel rankSummary(AnchorModels.Standings standings) {
		JPanel summary = card();
		JButton ladder = actionButton("Clan Rank · " + rankName(standings) + "  ›", false);
		ladder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		ladder.setAlignmentX(Component.LEFT_ALIGNMENT);
		ladder.addActionListener(e -> {
			rebuildRankLadder(standings);
			switchTab("Rank Ladder");
			SwingUtilities.invokeLater(() -> {
				rankScroll.getVerticalScrollBar().setValue(0);
				if (currentRankRow != null) currentRankRow.scrollRectToVisible(new java.awt.Rectangle(0, 0, currentRankRow.getWidth(), currentRankRow.getHeight()));
			});
		});
		summary.add(ladder);
		summary.add(Box.createVerticalStrut(8));
		JPanel balances = new JPanel(new GridLayout(1, 2, 4, 0));
		balances.setOpaque(false);
		balances.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton diary = actionButton(number(standings.pvmPoints) + " PvM ›", false);
		diary.getAccessibleContext().setAccessibleName("PvM Points, open Diary");
		diary.addActionListener(e -> switchTab("PvM Diary"));
		balances.add(diary);
		JButton clan = actionButton(number(standings.clanPoints) + " Clan ›", false);
		clan.addActionListener(e -> ladder.doClick());
		clan.setToolTipText("Clan Points · " + rank(standings.clanRank));
		diary.setToolTipText("PvM Points · " + rank(standings.pvmRank));
		balances.add(clan);
		summary.add(balances);
		AnchorModels.NextRank next = standings.nextRank;
		if (next != null && next.name != null) {
			summary.add(Box.createVerticalStrut(8));
			summary.add(label("Next · " + next.name, true));
			addRankProgress(summary, "PvM Points", standings.pvmPoints, next.pvmPoints, next.pvmPointsRemaining, PVM_ACCENT);
			addRankProgress(summary, "Clan Points", standings.clanPoints, next.clanPoints, next.clanPointsRemaining, CLAN_ACCENT);
			if (next.additionalRequirement != null && !next.additionalRequirement.isBlank())
				summary.add(wrappedMuted(next.additionalRequirement));
		}
		return summary;
	}

	// The profile API supplies only the next tier. Use the published thresholds for the other rows.
	private static final List<RankTier> RANK_TIERS = List.of(
			new RankTier("Zealot", 150, 50, null),
			new RankTier("Saradominist", 400, 100, null),
			new RankTier("Guthixian", 800, 200, null),
			new RankTier("Zamorakian", 1200, 300, null),
			new RankTier("Serenist", 1800, 400, null),
			new RankTier("Bandosian", 2600, 500, null),
			new RankTier("Zarosian", 3500, 600, null),
			new RankTier("Armadylean", 5000, 750, null),
			new RankTier("Xerician", 7500, 1000, null),
			new RankTier("TzTok", 8000, 1100, "All Master Combat Achievements achieved."),
			new RankTier("TzKal", 9000, 1300, "All Grandmaster Combat Achievements achieved."),
			new RankTier("Maxed", 10000, 1500, "All Grandmaster Combat Achievements achieved and 2277 total level."));

	private String expandedRank;

	private void rebuildRankLadder(AnchorModels.Standings standings) {
		rankPage.removeAll();
		currentRankRow = null;
		JButton back = actionButton("‹ Home", false);
		back.addActionListener(e -> switchTab("Profile"));
		rankPage.add(back);
		rankPage.add(pageHeading("Rank Ladder", standings == null ? "" : "Current rank · " + rankName(standings)));
		for (RankTier tier : RANK_TIERS) {
			AnchorModels.NextRank next = standings == null ? null : standings.nextRank;
			boolean upcoming = next != null && tier.name.equalsIgnoreCase(next.name);
			boolean current = standings != null && tier.name.equalsIgnoreCase(standings.currentRank);
			long pvmTarget = upcoming && next.pvmPoints != null ? next.pvmPoints : tier.pvm;
			long clanTarget = upcoming && next.clanPoints != null ? next.clanPoints : tier.clan;
			String extra = upcoming ? next.additionalRequirement : tier.extra;
			JPanel row = card();
			boolean expanded = tier.name.equals(expandedRank);
			JButton toggle = actionButton(tier.name + (current ? " · Current" : upcoming ? " · Next" : "")
					+ (expanded ? "  ▾" : "  ›"), current || upcoming);
			toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
			toggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
			toggle.addActionListener(e -> {
				expandedRank = tier.name.equals(expandedRank) ? null : tier.name;
				int scrollPosition = rankScroll.getVerticalScrollBar().getValue();
				rebuildRankLadder(standings);
				SwingUtilities.invokeLater(() -> rankScroll.getVerticalScrollBar().setValue(scrollPosition));
			});
			row.add(toggle);
			row.add(wrappedMuted(number(pvmTarget) + " PvM · " + number(clanTarget) + " Clan"));
			if (expanded) {
				if (standings != null) {
					addRankProgress(row, "PvM Points", standings.pvmPoints, pvmTarget,
							upcoming ? next.pvmPointsRemaining : Math.max(0, pvmTarget - standings.pvmPoints), PVM_ACCENT);
					addRankProgress(row, "Clan Points", standings.clanPoints, clanTarget,
							upcoming ? next.clanPointsRemaining : Math.max(0, clanTarget - standings.clanPoints), CLAN_ACCENT);
				}
				if (extra != null && !extra.isBlank()) row.add(wrappedMuted(extra));
			}
			if (current) currentRankRow = row;
			rankPage.add(row);
			rankPage.add(Box.createVerticalStrut(6));
		}
		AnchorTheme.typography(rankPage);
		rankPage.revalidate();
		rankPage.repaint();
	}

	private static String rankName(AnchorModels.Standings standings) {
		return standings.currentRank == null || standings.currentRank.isBlank() ? "Unranked" : standings.currentRank;
	}

	private static void addRankProgress(JPanel panel, String name, long current, Long target, long remaining, Color accent) {
		panel.add(Box.createVerticalStrut(8));
		long required = target == null ? Math.max(0, current + remaining) : target;
		long credited = Math.max(0, Math.min(current, required));
		panel.add(progress(name, credited, required - credited, accent, true));
	}

	private static final class RankTier {
		private final String name;
		private final long pvm;
		private final long clan;
		private final String extra;

		private RankTier(String name, long pvm, long clan, String extra) {
			this.name = name;
			this.pvm = pvm;
			this.clan = clan;
			this.extra = extra;
		}
	}

	private JPanel pvmDiary(AnchorModels.PvmDiaryStatus diary) {
		JPanel page = fullWidthVerticalPanel();
		if (diary == null) {
			JPanel empty = card();
			empty.add(wrappedMuted("Diary progress is currently unavailable"));
			page.add(empty);
			return page;
		}
		JPanel summary = card();
		summary.add(label("Progress", true));
		summary.add(Box.createVerticalStrut(12));
		summary.add(diarySummary(diary));
		summary.add(Box.createVerticalStrut(12));
		summary.add(AnchorTheme.bar(diary.earnedPoints, diary.totalPoints, PVM_ACCENT));
		if (diary.delivery != null && (diary.delivery.outstandingAwards > 0 || diary.delivery.reconciliationRequired)) {
			summary.add(Box.createVerticalStrut(10));
			summary.add(wrappedMuted(diary.delivery.reconciliationRequired ? "Points delivery needs reconciliation"
					: number(diary.delivery.outstandingAwards) + " points awaiting delivery"));
		}
		page.add(summary);
		page.add(Box.createVerticalStrut(18));
		page.add(label("Activities", true));
		page.add(Box.createVerticalStrut(8));
		if (diary.categories != null) for (AnchorModels.PvmDiaryCategory category : diary.categories) {
			page.add(diaryCategoryRow(category));
			page.add(Box.createVerticalStrut(6));
		}
		return page;
	}

	private static JPanel diarySummary(AnchorModels.PvmDiaryStatus diary) {
		JPanel summary = new JPanel(new GridLayout(1, 2, 8, 0));
		summary.setOpaque(false);
		summary.setAlignmentX(Component.LEFT_ALIGNMENT);
		summary.setMaximumSize(new Dimension(PANEL_WIDTH, 42));
		summary.add(diaryMetric("PvM Points", number(diary.earnedPoints), PVM_ACCENT));
		summary.add(diaryMetric("Tiers", diary.completedTiers + " / " + diary.totalTiers, CLAN_ACCENT));
		return summary;
	}

	private static JPanel diaryMetric(String title, String amount, Color color) {
		JPanel metric = new JPanel(new GridLayout(2, 1));
		metric.setOpaque(false);
		JLabel label = new JLabel(title, SwingConstants.LEFT);
		label.setForeground(new Color(145, 148, 151));
		label.setFont(AnchorTheme.font(Font.PLAIN, 11f));
		JLabel value = new JLabel(amount, SwingConstants.LEFT);
		value.setForeground(color);
		value.setFont(AnchorTheme.font(Font.BOLD, 19f));
		metric.add(label);
		metric.add(value);
		return metric;
	}

	private JPanel diaryCategoryRow(AnchorModels.PvmDiaryCategory category) {
		JPanel row = AnchorTheme.surface(PVM_ACCENT);
		row.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setOpaque(false);
		titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel name = new JLabel(diaryDisplayName(compactDiaryCategoryName(value(category.name))));
		name.setFont(AnchorTheme.font(Font.BOLD, 13));
		name.setForeground(AnchorTheme.TEXT);
		titleRow.add(name, BorderLayout.CENTER);
		JLabel arrow = new JLabel(category.completedTiers + "/" + category.totalTiers);
		arrow.setForeground(GOLD);
		arrow.setFont(AnchorTheme.font(Font.BOLD, 16));
		titleRow.add(arrow, BorderLayout.EAST);
		row.add(titleRow);
		row.add(Box.createVerticalStrut(5));
		JPanel detail = new JPanel(new BorderLayout());
		detail.setOpaque(false);
		detail.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel tiers = muted("tiers completed");
		JLabel points = muted(number(category.earnedPoints) + " / " + number(category.totalPoints) + " pts");
		points.setForeground(PVM_ACCENT);
		detail.add(tiers, BorderLayout.WEST);
		detail.add(points, BorderLayout.EAST);
		row.add(detail);
		row.add(Box.createVerticalStrut(9));
		row.add(AnchorTheme.bar(category.earnedPoints, category.totalPoints, GOLD));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		MouseAdapter click = new MouseAdapter() {
			@Override public void mouseClicked(MouseEvent e) { showDiaryCategoryDetails(category); }
		};
		installClick(row, click);
		row.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent e) { row.putClientProperty("hover", Boolean.TRUE); row.repaint(); }
			@Override public void mouseExited(MouseEvent e) {
				if (!row.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), row))) {
					row.putClientProperty("hover", Boolean.FALSE); row.repaint();
				}
			}
		});
		row.setFocusable(true);
		row.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "details");
		row.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("SPACE"), "details");
		row.getActionMap().put("details", new javax.swing.AbstractAction() {
			@Override public void actionPerformed(java.awt.event.ActionEvent e) { showDiaryCategoryDetails(category); }
		});
		row.getAccessibleContext().setAccessibleName(value(category.name) + ", " + category.completedTiers + " of " + category.totalTiers + " tiers. Open details.");
		return row;
	}

	private static void installClick(Component component, MouseAdapter listener) {
		component.addMouseListener(listener);
		if (component instanceof java.awt.Container)
			for (Component child : ((java.awt.Container) component).getComponents()) installClick(child, listener);
	}

	private static JLabel diaryCategoryLabel(String text, Color color) {
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(color);
		label.setFont(AnchorTheme.font(Font.BOLD, 11f));
		return label;
	}

	private static String diaryDisplayName(String raw) {
		if (raw == null || raw.isBlank()) return "—";
		String[] words = raw.replace('_', ' ').trim().split("\\s+");
		java.util.Set<String> abbreviations = java.util.Set.of("cox", "tob", "toa", "cm", "hmt", "cg", "pvm");
		for (int i = 0; i < words.length; i++) {
			String lower = words[i].toLowerCase(Locale.ROOT);
			if (abbreviations.contains(lower)) words[i] = lower.toUpperCase(Locale.ROOT);
			else if (lower.matches("[0-9]+s")) words[i] = lower;
			else words[i] = Character.toUpperCase(words[i].charAt(0)) + words[i].substring(1);
		}
		return String.join(" ", words);
	}

	private static String compactDiaryCategoryName(String name) {
		return "Corrupted Gauntlet".equalsIgnoreCase(name) ? "CG" : name;
	}

	private void showDiaryCategoryDetails(AnchorModels.PvmDiaryCategory category) {
		selectedDiaryCategory = category.name;
		rebuildDiaryPage();
		SwingUtilities.invokeLater(() -> diaryScroll.getVerticalScrollBar().setValue(0));
	}

	private void rebuildDiaryPage() {
		diaryPage.removeAll();
		AnchorModels.PvmDiaryStatus diary = data.pvmDiaryStatus();
		AnchorModels.PvmDiaryCategory selected = null;
		if (diary != null && diary.categories != null) for (AnchorModels.PvmDiaryCategory category : diary.categories)
			if (java.util.Objects.equals(selectedDiaryCategory, category.name)) { selected = category; break; }
		if (selected == null) {
			selectedDiaryCategory = null;
			diaryPage.add(pageHeading("PVM DIARY", ""));
			diaryPage.add(pvmDiary(diary));
		} else {
			JButton back = actionButton("‹  All activities", false);
			back.setAlignmentX(Component.LEFT_ALIGNMENT);
			back.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
			back.addActionListener(e -> {
				selectedDiaryCategory = null;
				rebuildDiaryPage();
				SwingUtilities.invokeLater(() -> diaryScroll.getVerticalScrollBar().setValue(0));
			});
			diaryPage.add(Box.createVerticalStrut(6));
			diaryPage.add(back);
			diaryPage.add(pageHeading(diaryDisplayName(value(selected.name)), ""));
			JPanel summary = card();
			summary.add(label(selected.completedTiers + " / " + selected.totalTiers + " tiers completed", true));
			summary.add(Box.createVerticalStrut(4));
			summary.add(muted(number(selected.earnedPoints) + " / " + number(selected.totalPoints) + " PvM points"));
			summary.add(Box.createVerticalStrut(8));
			summary.add(AnchorTheme.bar(selected.earnedPoints, selected.totalPoints, GOLD));
			diaryPage.add(summary);
			diaryPage.add(Box.createVerticalStrut(8));
			boolean found = false;
			if (diary.activities != null) for (AnchorModels.PvmDiaryActivity activity : diary.activities) {
				if (!java.util.Objects.equals(selected.name, activity.category)) continue;
				diaryPage.add(diaryActivityDetails(activity));
				diaryPage.add(Box.createVerticalStrut(6));
				found = true;
			}
			if (!found) diaryPage.add(wrappedMuted("No activity details are available yet."));
		}
		AnchorTheme.typography(diaryPage);
		diaryPage.revalidate();
		diaryPage.repaint();
	}

	private static JPanel diaryActivityDetails(AnchorModels.PvmDiaryActivity activity) {
		JPanel card = card();
		JLabel name = wrappedMuted(diaryDisplayName(value(activity.name)));
		name.setForeground(AnchorTheme.TEXT);
		name.setFont(AnchorTheme.font(Font.BOLD, 16));
		card.add(name);
		card.add(Box.createVerticalStrut(4));
		card.add(muted("Best: " + diaryBestResult(activity)));
		card.add(muted(number(activity.earnedPoints) + " points earned"));
		if (activity.tiers != null) for (AnchorModels.PvmDiaryTier tier : activity.tiers) {
			card.add(Box.createVerticalStrut(6));
			card.add(diaryTierRow(tier));
		}
		if (activity.pendingCount > 0) {
			card.add(Box.createVerticalStrut(6));
			card.add(wrappedMuted(activity.pendingCount + " submission" + (activity.pendingCount == 1 ? "" : "s") + " pending review"));
		}
		return card;
	}

	private static JPanel diaryTierRow(AnchorModels.PvmDiaryTier tier) {
		JPanel row = AnchorTheme.surface();
		row.putClientProperty("fill", tier.completed ? new Color(31, 49, 32) : AnchorTheme.BACKGROUND);
		row.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel name = label(diaryDisplayName(value(tier.tier)), true);
		name.setForeground(tier.completed ? CLAN_ACCENT : GOLD);
		JLabel points = muted((tier.completed ? "Done · " : "") + "+" + number(tier.points));
		points.setForeground(tier.completed ? CLAN_ACCENT : GOLD);
		top.add(name, BorderLayout.CENTER);
		top.add(points, BorderLayout.EAST);
		row.add(top);
		row.add(Box.createVerticalStrut(3));
		row.add(wrappedMuted(diaryDisplayName(value(tier.targetLabel))));
		return row;
	}

	private static String diaryBestResult(AnchorModels.PvmDiaryActivity activity) {
		if (activity.bestResult == null) return "—";
		if (activity.bestResult.label != null && !activity.bestResult.label.isBlank()) return activity.bestResult.label;
		if (activity.bestResult.value == null) return "—";
		if ("wave".equals(activity.kind)) return "Wave " + number(activity.bestResult.value);
		if ("completion".equals(activity.kind)) return "Complete";
		long totalSeconds = Math.max(0, activity.bestResult.value / 1000);
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
				: String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
	}

	static boolean requiresCompetitionAuthentication(AnchorDataService.Connection connection) {
		return connection == AnchorDataService.Connection.NOT_CONFIGURED
				|| connection == AnchorDataService.Connection.INVALID;
	}

	private JPanel competition(String kind, AnchorModels.CompetitionPanel competition, BufferedImage image,
			AnchorModels.CompetitionRank playerRank, String playerName, boolean expanded) {
		JPanel card = card();
		JButton toggle = actionButton((expanded ? "▾ " : "▸ ") + kind, false);
		toggle.setHorizontalAlignment(SwingConstants.LEFT);
		toggle.setToolTipText(expanded ? "Collapse " + kind : "Expand " + kind);
		toggle.addActionListener(e -> toggleCompetition(kind));
		toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
		toggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, NAV_BUTTON_HEIGHT));
		card.add(toggle);
		if (!expanded)
			return card;

		if ("ROTM".equals(kind) && competition != null) {
			String raidName = competition.metricLabel == null || competition.metricLabel.isBlank()
					? competition.title : competition.metricLabel;
			card.add(wrappedMuted(raidName));
			if (!isUpcoming(competition))
				card.add(muted(isFinished(competition) ? "Ended" : countdown(competition.endsAt)));
		} else {
			int cardContentWidth = PANEL_WIDTH - 32;
			BufferedImage scaledArtwork = scaleToWidth(image == null ? placeholderCompetition(kind) : image,
					cardContentWidth);
			JLabel artwork = new AspectRatioIconLabel();
			artwork.setIcon(new ImageIcon(scaledArtwork));
			artwork.setAlignmentX(Component.LEFT_ALIGNMENT);
			card.add(artwork);
		}
		card.add(Box.createVerticalStrut(5));
		boolean upcoming = isUpcoming(competition);
		boolean finished = isFinished(competition);
		if (competition == null) {
			card.add(muted("Competition data is currently unavailable"));
			return card;
		}
		if (upcoming) {
			Color accent = competitionAccent(kind);
			card.add(competitionSectionHeading("UPCOMING", accent));
			card.add(Box.createVerticalStrut(3));
			card.add(muted(startsCountdown(competition.startsAt)));
			return card;
		}
		List<AnchorModels.PanelLeader> leaders = competition.leaders == null ? java.util.Collections.emptyList()
				: competition.leaders;
		long leadingScore = leaders.stream().mapToLong(leader -> Math.max(0, leader.gained)).max().orElse(0);
		Color accent = competitionAccent(kind);
		// ROTM standings are optional: the profile API currently only supplies weekly ranks.
		boolean showPersonalProgress = (!"ROTM".equals(kind) || playerRank != null)
				&& (!finished || (playerRank != null && playerRank.rank != null && playerRank.rank <= 3));
		if (showPersonalProgress) {
			card.add(competitionSectionHeading("YOUR PROGRESS", accent));
			card.add(Box.createVerticalStrut(3));
			if (playerRank == null || playerRank.rank == null) {
				card.add(muted("No personal progress yet"));
			} else {
				String score = playerRank.displayValue == null || playerRank.displayValue.isBlank()
						? number(playerRank.gained) + " "
								+ value(playerRank.unit == null ? competition.unit : playerRank.unit)
						: playerRank.displayValue;
				card.add(leaderRow(playerRank.rank, value(playerName), score,
						leadingScore == 0 ? 0 : (double) Math.max(0, playerRank.gained) / leadingScore, accent, true));
			}
			card.add(Box.createVerticalStrut(6));
		}

		card.add(competitionSectionHeading(finished ? "WINNERS" : "LEADERBOARD", SCORE_COLOR));
		card.add(Box.createVerticalStrut(3));
		for (int i = 0; i < Math.min(3, leaders.size()); i++) {
			AnchorModels.PanelLeader leader = leaders.get(i);
			String nameValue = leader.displayName == null || leader.displayName.isBlank() ? value(leader.username)
					: leader.displayName;
			String score = leader.displayValue == null || leader.displayValue.isBlank()
					? number(leader.gained) + " " + value(leader.unit == null ? competition.unit : leader.unit)
					: leader.displayValue;
			card.add(leaderRow(leader.rank > 0 ? leader.rank : i + 1, nameValue, score,
					leadingScore == 0 ? 0 : (double) Math.max(0, leader.gained) / leadingScore, accent, false));
			if (i < Math.min(3, leaders.size()) - 1)
				card.add(Box.createVerticalStrut(3));
		}
		if (leaders.isEmpty()) {
			card.add(muted(finished ? "No completed competition history" : "No leaderboard history yet"));
		}
		return card;
	}

	private static Color competitionAccent(String kind) {
		return "ROTM".equals(kind) ? GOLD : "BOTW".equals(kind) ? BOTW_ACCENT : SOTW_ACCENT;
	}

	private void toggleCompetition(String kind) {
		if ("ROTM".equals(kind))
			rotmExpanded = !rotmExpanded;
		else if ("BOTW".equals(kind))
			botwExpanded = !botwExpanded;
		else
			sotwExpanded = !sotwExpanded;
		rebuildHome();
		home.revalidate();
		home.repaint();
	}

	private void rebuildSubmissions() {
		if (submissionsLoading) {
			submissionsReloadRequested = true;
			return;
		}
		submissionsLoading = true;
		new SwingWorker<List<EvidenceStore.Record>, Void>() {
			@Override protected List<EvidenceStore.Record> doInBackground() {
				return evidence.records();
			}

			@Override protected void done() {
				submissionsLoading = false;
				if (submissionsReloadRequested) {
					submissionsReloadRequested = false;
					rebuildSubmissions();
					return;
				}
				try {
					renderSubmissions(get());
				} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
					submissions.removeAll();
					submissions.add(muted("Unable to load local submissions."));
				}
				AnchorTheme.typography(submissions);
				submissions.revalidate();
				submissions.repaint();
			}
		}.execute();
	}

	private void renderSubmissions(List<EvidenceStore.Record> records) {
		submissions.removeAll();
		JComboBox<SubmissionFilter> filter = new JComboBox<>(SubmissionFilter.values());
		AnchorTheme.styleComboBox(filter);
		filter.setSelectedItem(submissionFilter);
		filter.setToolTipText("Show submissions by type");
		filter.addActionListener(e -> {
			submissionFilter = (SubmissionFilter) filter.getSelectedItem();
			rebuildSubmissions();
		});
		submissions.add(fieldRow("Show", filter));
		submissions.add(Box.createVerticalStrut(7));
		List<EvidenceStore.Record> visible = new java.util.ArrayList<>();
		for (EvidenceStore.Record record : records)
			if (submissionFilter.category == null || submissionFilter.category.equals(submissionCategory(record)))
				visible.add(record);
		if (visible.isEmpty()) {
			submissions.add(muted(records.isEmpty()
					? "Drops, diary entries, personal bests, and other evidence will appear here."
					: "No submissions match this filter."));
			return;
		}
		for (List<EvidenceStore.Record> group : submissionGroups(visible).values()) {
			EvidenceStore.Record record = preferredRecord(group);
			AnchorModels.EventStatus displayStatus = groupStatus(group);
			JPanel card = card();
			card.add(submissionHeading(record, displayStatus, group));
			if (record.error != null)
				card.add(wrappedMuted(record.error));
			JSpinner party = new JSpinner(new SpinnerNumberModel(
					record.metadata.party == null ? 1 : record.metadata.party.submittedPartySize, 1, 100, 1));
			party.setPreferredSize(new Dimension(54, party.getPreferredSize().height));
			int initialClan = record.metadata.party == null ? 0 : record.metadata.party.submittedClanMemberCount;
			int initialNonClan = record.metadata.party == null ? 0 : record.metadata.party.submittedNonClanMemberCount;
			JSpinner clanMembers = new JSpinner(new SpinnerNumberModel(initialClan, 0, 100, 1));
			JSpinner nonClanMembers = new JSpinner(new SpinnerNumberModel(initialNonClan, 0, 100, 1));
			clanMembers.setPreferredSize(new Dimension(54, clanMembers.getPreferredSize().height));
			nonClanMembers.setPreferredSize(new Dimension(54, nonClanMembers.getPreferredSize().height));
			JTextField notes = new JTextField();
			notes.setToolTipText("Optional submission notes");
			notes.setMaximumSize(new Dimension(Integer.MAX_VALUE, notes.getPreferredSize().height));
			PartyMemberEditor partyMemberEditor = record.metadata.party == null ? null
					: new PartyMemberEditor(record.metadata.party);
			if (record.metadata.party != null) {
				card.add(Box.createVerticalStrut(4));
				card.add(fieldRow("Party size", party));
				card.add(fieldRow("Clan members", clanMembers));
				card.add(fieldRow("Non-clan members", nonClanMembers));
				JButton editParty = actionButton("Edit party members", false);
				editParty.addActionListener(e -> partyMemberEditor.show(party, clanMembers, nonClanMembers));
				card.add(editParty);
				card.add(muted("Detected via " + friendlyMethod(record.metadata.party.method)));
				int unknown = Math.max(0, record.metadata.party.detectedPartySize
						- record.metadata.party.detectedClanMemberCount
						- record.metadata.party.detectedNonClanMemberCount);
				if (unknown > 0 && !"fixed_solo".equals(record.metadata.party.method))
					card.add(muted(unknown + " participant" + (unknown == 1 ? "" : "s") + " could not be classified"));
				String memberNames = partyMemberNames(record.metadata.party);
				if (!memberNames.isEmpty())
					card.add(wrappedMuted(memberNames));
			}
			if (displayStatus == AnchorModels.EventStatus.DRAFT && canFinalize(group)) {
				card.add(Box.createVerticalStrut(4));
				card.add(label("Notes", false));
				card.add(notes);
			}
			List<JButton> actionButtons = new java.util.ArrayList<>();
			EvidenceStore.Record proofRecord = proofRecord(group, record);
			if (proofRecord != null) {
				JButton proof = actionButton("View proof", false);
				proof.addActionListener(e -> showProof(proofRecord));
				actionButtons.add(proof);
			}
			if (displayStatus == AnchorModels.EventStatus.FAILED || displayStatus == AnchorModels.EventStatus.PENDING) {
				JButton retry = actionButton("Retry", true);
				retry.addActionListener(e -> runSubmissionAction(() -> {
					for (EvidenceStore.Record member : group)
						if (member.status == AnchorModels.EventStatus.PENDING || member.status == AnchorModels.EventStatus.FAILED)
							pipeline.upload(member, true);
				}));
				actionButtons.add(retry);
			}
			if (displayStatus == AnchorModels.EventStatus.DRAFT && canFinalize(group)) {
				JButton submit = actionButton("Submit", true);
				submit.addActionListener(e -> {
					int partySize = (Integer) party.getValue();
					int clanCount = (Integer) clanMembers.getValue();
					int nonClanCount = (Integer) nonClanMembers.getValue();
					if (clanCount + nonClanCount > partySize) {
						JOptionPane.showMessageDialog(this, "Clan and non-clan members cannot exceed party size.");
						return;
					}
					String submissionNotes = notes.getText();
					List<AnchorModels.PartyMember> partyMembers = partyMemberEditor == null ? List.of()
							: partyMemberEditor.members();
					runSubmissionAction(() -> {
						for (EvidenceStore.Record member : group)
							if (member.status == AnchorModels.EventStatus.DRAFT)
								pipeline.updateAndSubmit(member, partySize, clanCount, nonClanCount, partyMembers, submissionNotes);
					});
				});
				actionButtons.add(submit);
			}
			JButton discard = actionButton("Discard", false);
			discard.addActionListener(e -> {
				if (JOptionPane.showConfirmDialog(this, "Remove this local evidence?", "Discard",
						JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
					runSubmissionAction(() -> {
						for (EvidenceStore.Record member : group) evidence.deleteLocal(member.metadata.eventId);
					});
				}
			});
			actionButtons.add(discard);
			JPanel actions = actionGrid(actionButtons);
			card.add(Box.createVerticalStrut(5));
			card.add(actions);
			submissions.add(card);
			submissions.add(Box.createVerticalStrut(7));
		}
	}

	private void runSubmissionAction(Runnable action) {
		new SwingWorker<Void, Void>() {
			@Override protected Void doInBackground() {
				action.run();
				return null;
			}

			@Override protected void done() {
				try {
					get();
				} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
					JOptionPane.showMessageDialog(AnchorPanel.this, "Unable to update local submission.");
				}
				refresh();
			}
		}.execute();
	}

	private static java.util.Map<String, List<EvidenceStore.Record>> submissionGroups(List<EvidenceStore.Record> records) {
		java.util.Map<String, List<EvidenceStore.Record>> groups = new java.util.LinkedHashMap<>();
		for (EvidenceStore.Record record : records) {
			String groupId = EventPipeline.submissionGroupId(record);
			String key = groupId == null ? "event:" + record.metadata.eventId : "group:" + groupId;
			// Keep diary and PB status/actions independent, even for one encounter.
			String type = record.metadata.eventType;
			if ("diary".equals(type) || "personal_best".equals(type)) key += ':' + type;
			groups.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(record);
		}
		return groups;
	}

	private static String submissionCategory(EvidenceStore.Record record) {
		String type = record.metadata.eventType;
		if ("diary".equals(type) || "personal_best".equals(type)) return type;
		if ("loot".equals(type) || "bingo".equals(type) || "collection_log".equals(type) || "pet".equals(type)) return "loot";
		return "other";
	}

	private static EvidenceStore.Record preferredRecord(List<EvidenceStore.Record> group) {
		EvidenceStore.Record preferred = group.get(0);
		for (EvidenceStore.Record record : group) {
			if (eventPriority(record) < eventPriority(preferred)) preferred = record;
		}
		return preferred;
	}

	private static int eventPriority(EvidenceStore.Record record) {
		String type = record == null || record.metadata == null ? null : record.metadata.eventType;
		if ("bingo".equals(type)) return 0;
		if ("loot".equals(type)) return 1;
		if ("personal_best".equals(type)) return 2;
		return 3;
	}

	private static AnchorModels.EventStatus groupStatus(List<EvidenceStore.Record> group) {
		if (group == null || group.isEmpty()) return AnchorModels.EventStatus.PENDING;
		for (AnchorModels.EventStatus candidate : new AnchorModels.EventStatus[] {
			AnchorModels.EventStatus.FAILED, AnchorModels.EventStatus.PENDING, AnchorModels.EventStatus.UPLOADING,
			AnchorModels.EventStatus.DRAFT, AnchorModels.EventStatus.REJECTED, AnchorModels.EventStatus.SUBMITTED,
			AnchorModels.EventStatus.APPROVED }) {
			for (EvidenceStore.Record record : group) if (record.status == candidate) return candidate;
		}
		return group.get(0).status;
	}

	private boolean canFinalize(List<EvidenceStore.Record> group) {
		if (group == null || !config.autoSubmitEnabled()) return true;
		for (EvidenceStore.Record record : group) {
			if (record == null || record.metadata == null || record.metadata.context == null) continue;
			// A locally-created draft can explicitly opt out of automatic
			// submission while still being intended for manual submission.
			if (record.metadata.details != null
					&& Boolean.FALSE.equals(record.metadata.details.get("autoSubmit"))) continue;
			Object value = record.metadata.context.get("finalizeSubmission");
			if (Boolean.FALSE.equals(value) || "false".equalsIgnoreCase(String.valueOf(value))) return false;
		}
		return true;
	}

	private static EvidenceStore.Record proofRecord(List<EvidenceStore.Record> group, EvidenceStore.Record fallback) {
		if (fallback != null && fallback.screenshotPath != null && !fallback.screenshotPath.isBlank()) return fallback;
		if (group != null)
			for (EvidenceStore.Record record : group)
				if (record != null && record.screenshotPath != null && !record.screenshotPath.isBlank()) return record;
		return null;
	}

	private static String partyMemberNames(AnchorModels.Party party) {
		List<AnchorModels.PartyMember> members = party == null ? List.of()
				: party.submittedMembers == null || party.submittedMembers.isEmpty() ? party.members : party.submittedMembers;
		if (members.isEmpty())
			return "";
		StringBuilder names = new StringBuilder("Party: ");
		for (AnchorModels.PartyMember member : members) {
			if (member == null || member.name == null || member.name.isBlank())
				continue;
			if (names.length() > 7)
				names.append(", ");
			names.append(member.name);
			if (member.clanMember != null)
				names.append(Boolean.TRUE.equals(member.clanMember) ? " [clan]" : " [guest]");
		}
		return names.length() == 7 ? "" : names.toString();
	}

	private final class PartyMemberEditor {
		private final List<AnchorModels.PartyMember> members = new java.util.ArrayList<>();

		PartyMemberEditor(AnchorModels.Party party) {
			List<AnchorModels.PartyMember> source = party.submittedMembers == null || party.submittedMembers.isEmpty()
					? party.members : party.submittedMembers;
			for (AnchorModels.PartyMember member : source) members.add(copyPartyMember(member));
		}

		void show(JSpinner partySize, JSpinner clanMembers, JSpinner nonClanMembers) {
			JSpinner size = new JSpinner(new SpinnerNumberModel(((Integer) partySize.getValue()).intValue(), 1, 100, 1));
			JPanel roster = verticalPanel();
			size.addChangeListener(e -> rebuildRoster(roster, (Integer) size.getValue()));
			rebuildRoster(roster, (Integer) size.getValue());
			JPanel content = verticalPanel();
			content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
			content.add(fieldRow("Party size", size));
			content.add(Box.createVerticalStrut(5));
			content.add(label("Members", false));
			JScrollPane scroll = scroll(roster);
			scroll.setPreferredSize(new Dimension(360, 190));
			content.add(scroll);
			int choice = JOptionPane.showConfirmDialog(AnchorPanel.this, content, "Edit party members",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) return;
			readRoster(roster);
			partySize.setValue(size.getValue());
			int clan = 0, nonClan = 0;
			for (AnchorModels.PartyMember member : members) {
				if (Boolean.TRUE.equals(member.clanMember)) clan++;
				else if (Boolean.FALSE.equals(member.clanMember)) nonClan++;
			}
			clanMembers.setValue(clan);
			nonClanMembers.setValue(nonClan);
		}

		List<AnchorModels.PartyMember> members() {
			List<AnchorModels.PartyMember> copy = new java.util.ArrayList<>();
			for (AnchorModels.PartyMember member : members) copy.add(copyPartyMember(member));
			return copy;
		}

		private void rebuildRoster(JPanel roster, int count) {
			readRoster(roster);
			while (members.size() < count) members.add(new AnchorModels.PartyMember());
			while (members.size() > count) members.remove(members.size() - 1);
			roster.removeAll();
			for (int index = 0; index < members.size(); index++) {
				AnchorModels.PartyMember member = members.get(index);
				JPanel row = new JPanel(new BorderLayout(5, 0));
				row.setOpaque(false);
				JTextField name = new JTextField(member.name == null ? "" : member.name);
				name.putClientProperty("partyIndex", index);
				name.setToolTipText("RuneScape name");
				JComboBox<String> role = new JComboBox<>(new String[] { "Unknown", "Clan", "Guest" });
				AnchorTheme.styleComboBox(role);
				role.setSelectedItem(Boolean.TRUE.equals(member.clanMember) ? "Clan"
						: Boolean.FALSE.equals(member.clanMember) ? "Guest" : "Unknown");
				role.putClientProperty("partyIndex", index);
				row.add(new JLabel((index + 1) + "."), BorderLayout.WEST);
				row.add(name, BorderLayout.CENTER);
				row.add(role, BorderLayout.EAST);
				roster.add(row);
				if (index < members.size() - 1) roster.add(Box.createVerticalStrut(3));
			}
			roster.revalidate(); roster.repaint();
		}

		private void readRoster(JPanel roster) {
			for (Component component : roster.getComponents()) {
				if (!(component instanceof JPanel)) continue;
				for (Component field : ((JPanel) component).getComponents()) {
					Object index = field instanceof javax.swing.JComponent
							? ((javax.swing.JComponent) field).getClientProperty("partyIndex") : null;
					if (!(index instanceof Integer) || (Integer) index >= members.size()) continue;
					AnchorModels.PartyMember member = members.get((Integer) index);
					if (field instanceof JTextField) member.name = ((JTextField) field).getText().trim();
					if (field instanceof JComboBox) {
						String role = String.valueOf(((JComboBox<?>) field).getSelectedItem());
						member.clanMember = "Clan".equals(role) ? Boolean.TRUE : "Guest".equals(role) ? Boolean.FALSE : null;
					}
				}
			}
		}

		private AnchorModels.PartyMember copyPartyMember(AnchorModels.PartyMember source) {
			AnchorModels.PartyMember copy = new AnchorModels.PartyMember();
			if (source != null) { copy.name = source.name; copy.clanMember = source.clanMember; }
			return copy;
		}
	}

	private JPanel submissionHeading(EvidenceStore.Record record, AnchorModels.EventStatus displayStatus,
			List<EvidenceStore.Record> group) {
		JPanel heading = new JPanel(new BorderLayout(7, 0));
		heading.setOpaque(false);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel text = verticalPanel();
		text.setOpaque(false);
		text.setOpaque(false);
		text.add(sectionHeading(eventLabel(record, group)));
		text.add(Box.createVerticalStrut(2));
		JLabel title = new JLabel(title(record));
		title.setForeground(Color.WHITE);
		title.setFont(AnchorTheme.font(Font.BOLD, 13f));
		title.setToolTipText(title.getText());
		text.add(title);
		heading.add(text, BorderLayout.CENTER);
		heading.add(statusBadge(displayStatus), BorderLayout.EAST);
		Integer itemId = submissionItemId(record);
		String activity = pbActivity(record);
		if (itemId != null) {
			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(30, 30));
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			AsyncBufferedImage image = itemManager.getImage(itemId);
			Runnable update = () -> {
				icon.setIcon(new ImageIcon(scale(image, 28, 28)));
				icon.repaint();
			};
			update.run();
			image.onLoaded(() -> SwingUtilities.invokeLater(update));
			heading.add(icon, BorderLayout.WEST);
		} else if (activity != null) {
			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(30, 30));
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			BossIcons.get(spriteManager, activity, loaded -> {
				icon.setIcon(loaded);
				icon.repaint();
			});
			heading.add(icon, BorderLayout.WEST);
		}
		heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(32, heading.getPreferredSize().height)));
		return heading;
	}

	private static JPanel actionGrid(List<JButton> buttons) {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (int i = 0; i < buttons.size(); i++) {
			boolean fullWidthLast = buttons.size() % 2 == 1 && i == buttons.size() - 1;
			GridBagConstraints constraints = new GridBagConstraints();
			constraints.gridx = fullWidthLast ? 0 : i % 2;
			constraints.gridy = i / 2;
			constraints.gridwidth = fullWidthLast ? 2 : 1;
			constraints.weightx = fullWidthLast ? 2.0 : 1.0;
			constraints.fill = GridBagConstraints.HORIZONTAL;
			constraints.insets = fullWidthLast ? new Insets(i >= 2 ? 4 : 0, 0, 0, 0)
					: new Insets(i >= 2 ? 4 : 0, i % 2 == 1 ? 3 : 0, 0, i % 2 == 0 ? 3 : 0);
			panel.add(buttons.get(i), constraints);
		}
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	static JButton actionButton(String text, boolean primary) {
		JButton button = new JButton(text);
		button.setForeground(primary ? GOLD : AnchorTheme.MUTED);
		button.setFont(AnchorTheme.font(Font.BOLD, 11f));
		button.setFocusPainted(false);
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setMargin(new Insets(0, 8, 0, 8));
		button.setPreferredSize(new Dimension(0, 28));
		button.setBackground(AnchorTheme.BACKGROUND);
		button.setRolloverEnabled(true);
		button.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
			@Override
			public void paint(Graphics graphics, javax.swing.JComponent component) {
				JButton target = (JButton) component;
				Graphics2D g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				boolean navigation = Boolean.TRUE.equals(target.getClientProperty("navigation"));
				Color background = target.getBackground();
				if (target.getModel().isPressed()) background = background.darker();
				else if (target.getModel().isRollover() && !target.isSelected()) background = new Color(35, 38, 42);
				g.setColor(background);
				g.fillRoundRect(0, 0, target.getWidth(), target.getHeight(), 6, 6);
				if (!navigation) {
					Color accent = primary ? GOLD : AnchorTheme.MUTED;
					g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 120));
					g.drawRoundRect(0, 0, target.getWidth() - 1, target.getHeight() - 1, 6, 6);
				}
				g.dispose();
				super.paint(graphics, component);
			}
		});
		return button;
	}

	private static JLabel statusBadge(AnchorModels.EventStatus status) {
		String text = status == null ? "UNKNOWN" : status.name();
		Color color = status == AnchorModels.EventStatus.SUBMITTED || status == AnchorModels.EventStatus.APPROVED
				? CONNECTED_COLOR
				: status == AnchorModels.EventStatus.FAILED || status == AnchorModels.EventStatus.REJECTED
						? new Color(194, 83, 80)
						: status == AnchorModels.EventStatus.DRAFT ? GOLD : new Color(150, 154, 158);
		JLabel badge = new JLabel(text, SwingConstants.CENTER);
		badge.setForeground(color);
		badge.setFont(AnchorTheme.font(Font.BOLD, 10f));
		badge.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(new Color(color.getRed(), color.getGreen(), color.getBlue(), 135)),
				BorderFactory.createEmptyBorder(3, 6, 3, 6)));
		badge.setAlignmentY(Component.CENTER_ALIGNMENT);
		return badge;
	}

	private static JPanel fieldRow(String name, Component field) {
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel label = new JLabel(name);
		label.setForeground(Color.WHITE);
		row.add(label, BorderLayout.CENTER);
		row.add(field, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private void showProof(EvidenceStore.Record record) {
		try {
			BufferedImage image = ImageIO.read(Path.of(record.screenshotPath).toFile());
			if (image == null)
				throw new IllegalArgumentException("Unsupported evidence image");
			Window owner = SwingUtilities.getWindowAncestor(this);
			JDialog dialog = new JDialog(owner, "Evidence", Dialog.ModalityType.APPLICATION_MODAL);
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
			dialog.setResizable(true);
			dialog.setMinimumSize(new Dimension(360, 260));
			dialog.add(new EvidenceImagePanel(image), BorderLayout.CENTER);
			Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
			dialog.setSize(Math.min(900, screen.width - 80), Math.min(700, screen.height - 80));
			dialog.setLocationRelativeTo(this);
			dialog.setVisible(true);
		} catch (Exception e) {
			JOptionPane.showMessageDialog(this, "Evidence file is unavailable.");
		}
	}

	private static JPanel verticalPanel() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(AnchorTheme.BACKGROUND);
		return panel;
	}

	private static JPanel fullWidthVerticalPanel() {
		JPanel panel = new JPanel() {
			@Override
			public Dimension getMaximumSize() {
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(AnchorTheme.BACKGROUND);
		return panel;
	}

	private static JPanel pageHeading(String title, String subtitle) {
		JPanel heading = fullWidthVerticalPanel();
		heading.setBorder(BorderFactory.createEmptyBorder(8, 0, 10, 0));
		JLabel label = new JLabel(title);
		label.setFont(AnchorTheme.font(Font.BOLD, 16f));
		label.setForeground(GOLD);
		heading.add(label);
		if (!subtitle.isEmpty()) {
			heading.add(Box.createVerticalStrut(4));
			heading.add(wrappedMuted(subtitle));
		}
		return heading;
	}

	private static JPanel card() { return AnchorTheme.surface(); }

	static JScrollPane scroll(JPanel panel) {
		return scroll(panel, false);
	}

	static JScrollPane scroll(JPanel panel, boolean reserveScrollbarSpace) {
		ViewportWidthPanel viewport = new ViewportWidthPanel();
		viewport.setBorder(BorderFactory.createEmptyBorder(0, 6, 8, 6));
		viewport.add(panel, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(viewport);
		scroll.setBorder(null);
		scroll.setBackground(AnchorTheme.BACKGROUND);
		scroll.getViewport().setBackground(AnchorTheme.BACKGROUND);
		scroll.getViewport().setOpaque(true);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		// A narrow scrollbar leaves room for content at standard sidebar widths.
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getVerticalScrollBar().setPreferredSize(new Dimension(6, 0));
		scroll.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
			@Override protected void configureScrollBarColors() { thumbColor = new Color(68, 73, 81); trackColor = AnchorTheme.BACKGROUND; }
			@Override protected JButton createDecreaseButton(int orientation) { return hiddenScrollButton(); }
			@Override protected JButton createIncreaseButton(int orientation) { return hiddenScrollButton(); }
			private JButton hiddenScrollButton() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
		});
		return scroll;
	}

	private static final class ViewportWidthPanel extends JPanel implements Scrollable {
		private ViewportWidthPanel() {
			super(new BorderLayout());
			setBackground(AnchorTheme.BACKGROUND);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
			return Math.max(16, visible.height - 16);
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			// Preserve the cards' natural height instead of switching between fitting and scrolling.
			return false;
		}
	}

	private static JLabel label(String text, boolean bold) {
		JLabel label = new JLabel(html(esc(text)));
		label.setForeground(Color.WHITE);
		if (bold)
			label.setFont(AnchorTheme.font(Font.BOLD, 13f));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setMaximumSize(new Dimension(PANEL_WIDTH, 40));
		return label;
	}

	private static JLabel muted(String text) {
		JLabel label = new JLabel(text);
		label.putClientProperty("html.disable", Boolean.TRUE);
		label.setFont(AnchorTheme.font(Font.PLAIN, 11));
		label.setForeground(AnchorTheme.MUTED);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setMaximumSize(new Dimension(PANEL_WIDTH, 40));
		return label;
	}

	private static JLabel wrappedMuted(String text) {
		JLabel label = muted(text);
		label.putClientProperty("html.disable", Boolean.FALSE);
		label.setText("<html><div style='width:" + (PANEL_WIDTH - 60) + "px'>" + esc(text) + "</div></html>");
		label.setMaximumSize(new Dimension(PANEL_WIDTH, Integer.MAX_VALUE));
		return label;
	}

	private static JPanel row(String leftLabel, String leftValue, String rightLabel, String rightValue) {
		JPanel p = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(PANEL_WIDTH, 20));
		JLabel left = new JLabel(leftLabel + " " + leftValue);
		left.setForeground(Color.WHITE);
		JLabel right = new JLabel((rightLabel + " " + rightValue).trim());
		right.setForeground(Color.WHITE);
		p.add(left);
		p.add(right);
		return p;
	}

	private static JLabel sectionHeading(String text) {
		JLabel heading = new JLabel(text);
		heading.setForeground(new Color(155, 158, 161));
		heading.setFont(AnchorTheme.font(Font.BOLD, 12f));
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		return heading;
	}

	private static JLabel competitionSectionHeading(String text, Color color) {
		JLabel heading = new JLabel(text);
		heading.setForeground(color);
		heading.setFont(AnchorTheme.font(Font.BOLD, 11f));
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		return heading;
	}

	private static JPanel weeklyTimer(String timing) {
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setBackground(new Color(38, 41, 44));
		p.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 3, 0, 0, GOLD),
				BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(PANEL_WIDTH, 42));
		JLabel title = new JLabel("WEEKLY EVENTS");
		title.setForeground(new Color(155, 158, 161));
		title.setFont(AnchorTheme.font(Font.BOLD, 11f));
		JLabel value = new JLabel(timing.toUpperCase(Locale.ROOT), SwingConstants.RIGHT);
		value.setForeground(SCORE_COLOR);
		value.setFont(AnchorTheme.font(Font.BOLD, 12f));
		p.add(title, BorderLayout.WEST);
		p.add(value, BorderLayout.EAST);
		return p;
	}

	private static JPanel leaderRow(int rank, String name, String score, double progress, Color accent,
			boolean highlighted) {
		JPanel p = new JPanel(new BorderLayout(6, 0)) {
			@Override
			protected void paintComponent(Graphics g) {
				super.paintComponent(g);
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(highlighted ? new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 38)
						: new Color(255, 255, 255, 10));
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
				int barWidth = (int) Math.round(Math.max(0, Math.min(1, progress)) * getWidth());
				g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 72));
				g2.fillRoundRect(0, 0, barWidth, getHeight(), 6, 6);
				g2.dispose();
			}
		};
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(PANEL_WIDTH, 28));
		p.setPreferredSize(new Dimension(PANEL_WIDTH, 28));

		JLabel rankLabel = new JLabel(String.valueOf(rank), SwingConstants.CENTER);
		rankLabel.setForeground(rank <= RANK_COLORS.length
				? RANK_COLORS[Math.max(0, rank - 1)]
				: highlighted ? accent : new Color(160, 163, 166));
		rankLabel.setFont(AnchorTheme.font(Font.BOLD, 12f));
		rankLabel.setPreferredSize(new Dimension(18, 18));
		JLabel nameLabel = new JLabel(name);
		nameLabel.setForeground(new Color(225, 225, 225));
		nameLabel.setFont(AnchorTheme.font(Font.BOLD, 12f));
		nameLabel.setToolTipText(name);
		JLabel scoreLabel = new JLabel(styledScore(score), SwingConstants.RIGHT);
		scoreLabel.setForeground(SCORE_COLOR);
		scoreLabel.setFont(AnchorTheme.font(Font.BOLD, 12f));
		scoreLabel.setToolTipText(score);
		int scoreWidth = Math.min(90, scoreLabel.getPreferredSize().width);
		scoreLabel.setPreferredSize(new Dimension(scoreWidth, 18));
		p.add(rankLabel, BorderLayout.WEST);
		p.add(nameLabel, BorderLayout.CENTER);
		p.add(scoreLabel, BorderLayout.EAST);
		return p;
	}

	private static String styledScore(String score) {
		String escaped = esc(score);
		int split = escaped.lastIndexOf(' ');
		if (split < 0)
			return escaped;
		return "<html>" + escaped.substring(0, split) + " <span style='color:#9da1a4;font-size:10px'>"
				+ escaped.substring(split + 1) + "</span></html>";
	}

	private static String sharedCompetitionTiming(AnchorModels.CompetitionPanels competitions) {
		if (competitions == null)
			return "";
		boolean botwUpcoming = competitions.botw != null && isUpcoming(competitions.botw);
		boolean sotwUpcoming = competitions.sotw != null && isUpcoming(competitions.sotw);
		if (botwUpcoming && sotwUpcoming) {
			String botwStart = competitions.botw.startsAt;
			String sotwStart = competitions.sotw.startsAt;
			return startsCountdown(botwStart == null || botwStart.isBlank() ? sotwStart : botwStart);
		}
		boolean botwFinished = competitions.botw != null && isFinished(competitions.botw);
		boolean sotwFinished = competitions.sotw != null && isFinished(competitions.sotw);
		if (botwFinished && sotwFinished) {
			return "Competitions Ended";
		}
		String botwEnd = competitions.botw == null ? null : competitions.botw.endsAt;
		String sotwEnd = competitions.sotw == null ? null : competitions.sotw.endsAt;
		return countdown(botwEnd == null || botwEnd.isBlank() ? sotwEnd : botwEnd);
	}

	private static JPanel progress(String name, long current, long remaining, Color accent) {
		return progress(name, current, remaining, accent, false);
	}

	private static JPanel progress(String name, long current, long remaining, Color accent, boolean showRemaining) {
		JPanel panel = fullWidthVerticalPanel();
		panel.setOpaque(false);
		JPanel labels = new JPanel(new BorderLayout());
		labels.setOpaque(false);
		labels.setAlignmentX(Component.LEFT_ALIGNMENT);
		labels.add(muted(name), BorderLayout.WEST);
		JLabel amount = muted(number(current) + " / " + number(current + remaining));
		amount.setForeground(AnchorTheme.TEXT);
		labels.add(amount, BorderLayout.EAST);
		panel.add(labels);
		panel.add(Box.createVerticalStrut(7));
		panel.add(AnchorTheme.bar(current, current + remaining, accent));
		if (showRemaining) {
			panel.add(Box.createVerticalStrut(5));
			panel.add(muted(remaining > 0 ? number(remaining) + " to go" : "Requirement met"));
		}
		return panel;
	}

	private static String eventLabel(EvidenceStore.Record record, List<EvidenceStore.Record> group) {
		String type = record.metadata == null ? null : record.metadata.eventType;
		if (type == null)
			return "SUBMISSION";
		java.util.Set<String> groupedTypes = new java.util.HashSet<>();
		if (group != null)
			for (EvidenceStore.Record member : group)
				if (member != null && member.metadata != null && member.metadata.eventType != null)
					groupedTypes.add(member.metadata.eventType);
		if (groupedTypes.size() > 1) {
			List<String> labels = new java.util.ArrayList<>();
			if (groupedTypes.contains("bingo")) labels.add("BINGO");
			if (groupedTypes.contains("loot")) labels.add("LOOT");
			if (groupedTypes.contains("personal_best")) labels.add("PB");
			for (String groupedType : groupedTypes)
				if (!"bingo".equals(groupedType) && !"loot".equals(groupedType) && !"personal_best".equals(groupedType))
					labels.add(groupedType.replace('_', ' ').toUpperCase(Locale.ROOT));
			return String.join("+", labels);
		}
		switch (type) {
			case "bingo":
				return "BINGO";
			case "loot":
				return "LOOT DROP";
			case "personal_best":
				return "PERSONAL BEST";
			case "diary":
				return "PVM DIARY";
			case "collection_log":
				return "COLLECTION LOG";
			case "pet":
				return "PET";
			case "combat_achievement_tier":
				return "COMBAT ACHIEVEMENT";
			default:
				return type.replace('_', ' ').toUpperCase(Locale.ROOT);
		}
	}

	private static boolean isPersonalBest(EvidenceStore.Record record) {
		return record != null && record.metadata != null && "personal_best".equals(record.metadata.eventType);
	}

	private static String friendlyMethod(String method) {
		if (method == null || method.isBlank() || "unknown".equalsIgnoreCase(method))
			return "game activity";
		return method.replace('_', ' ');
	}

	private static String pbActivity(EvidenceStore.Record record) {
		if (record == null || record.metadata == null || record.metadata.details == null)
			return null;
		if (!isPersonalBest(record) && !"diary".equals(record.metadata.eventType))
			return null;
		Object pb = record.metadata.details.get("record");
		if (pb instanceof AnchorModels.PbRecord)
			return value(((AnchorModels.PbRecord) pb).activity);
		if (pb instanceof java.util.Map) {
			Object activity = ((java.util.Map<?, ?>) pb).get("activity");
			if (activity != null && !String.valueOf(activity).isBlank())
				return String.valueOf(activity);
		}
		return null;
	}

	static Integer submissionItemId(EvidenceStore.Record record) {
		if (record == null || record.metadata == null || record.metadata.items == null
				|| record.metadata.items.isEmpty())
			return null;
		int itemId = record.metadata.items.get(0).itemId;
		return itemId > 0 ? itemId : null;
	}

	private static String title(EvidenceStore.Record record) {
		if (record.metadata == null)
			return "Submission";
		if (record.metadata.items != null && !record.metadata.items.isEmpty())
			return value(record.metadata.items.get(0).name);
		String detailTitle = firstDetail(record, "itemName", "petName", "taskName");
		if (detailTitle != null)
			return detailTitle;
		String activity = pbActivity(record);
		if (activity != null)
			return titleCase(activity);
		String type = record.metadata.eventType == null ? "submission" : record.metadata.eventType.replace('_', ' ');
		return capitalize(type);
	}

	private static String firstDetail(EvidenceStore.Record record, String... keys) {
		if (record.metadata.details == null)
			return null;
		for (String key : keys) {
			Object value = record.metadata.details.get(key);
			if (value != null && !String.valueOf(value).isBlank())
				return String.valueOf(value);
		}
		return null;
	}

	private static String rank(Integer value) {
		return value == null ? "—" : "#" + value;
	}

	private static String number(long value) {
		return NumberFormat.getIntegerInstance(Locale.US).format(value);
	}

	private static String value(String value) {
		return value == null || value.isBlank() ? "—" : value;
	}

	private static boolean isFinished(AnchorModels.CompetitionPanel competition) {
		if (competition == null)
			return false;
		if ("finished".equalsIgnoreCase(competition.status)
				|| "ended".equalsIgnoreCase(competition.status)
				|| "completed".equalsIgnoreCase(competition.status)
				|| "inactive".equalsIgnoreCase(competition.status)) {
			return true;
		}
		if (competition.endsAt != null && !competition.endsAt.isBlank()) {
			try {
				return Instant.parse(competition.endsAt).isBefore(Instant.now());
			} catch (RuntimeException ignored) {
			}
		}
		return false;
	}

	private static boolean isUpcoming(AnchorModels.CompetitionPanel competition) {
		if (competition == null)
			return false;
		if ("upcoming".equalsIgnoreCase(competition.status))
			return true;
		if (competition.startsAt != null && !competition.startsAt.isBlank()) {
			try {
				return Instant.parse(competition.startsAt).isAfter(Instant.now());
			} catch (RuntimeException ignored) {
			}
		}
		return false;
	}

	private static String startsCountdown(String start) {
		try {
			Duration d = Duration.between(Instant.now(), Instant.parse(start));
			if (d.isNegative())
				return "Starting now";
			long days = d.toDays();
			long hours = d.minusDays(days).toHours();
			long minutes = d.minusDays(days).minusHours(hours).toMinutes();
			return "Starts in " + (days > 0 ? days + "d " : "") + (hours > 0 ? hours + "h " : "") + minutes + "m";
		} catch (RuntimeException e) {
			return "Starts soon";
		}
	}

	private static String countdown(String end) {
		try {
			Duration d = Duration.between(Instant.now(), Instant.parse(end));
			if (d.isNegative())
				return "Ended";
			return "Ends in " + d.toDays() + "d " + d.minusDays(d.toDays()).toHours() + "h";
		} catch (RuntimeException e) {
			return "";
		}
	}

	private static String capitalize(String value) {
		return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	private static String titleCase(String value) {
		if (value == null || value.isBlank())
			return value;
		String[] words = value.toLowerCase(Locale.ROOT).split("\\s+");
		StringBuilder result = new StringBuilder(value.length());
		for (String word : words) {
			if (result.length() > 0)
				result.append(' ');
			result.append(capitalize(word));
		}
		return result.toString();
	}

	private static String html(String value) {
		return "<html>" + value + "</html>";
	}

	private static String esc(String value) {
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static BufferedImage scale(BufferedImage image, int maxWidth, int maxHeight) {
		double factor = Math.min((double) maxWidth / image.getWidth(), (double) maxHeight / image.getHeight());
		factor = Math.min(1.0, factor);
		int width = Math.max(1, (int) (image.getWidth() * factor));
		int height = Math.max(1, (int) (image.getHeight() * factor));
		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = scaled.createGraphics();
		g.drawImage(image, 0, 0, width, height, null);
		g.dispose();
		return scaled;
	}

	private static BufferedImage scaleToWidth(BufferedImage image, int width) {
		double factor = (double) width / image.getWidth();
		int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = scaled.createGraphics();
		g.drawImage(image, 0, 0, width, height, null);
		g.dispose();
		return scaled;
	}

	private static BufferedImage placeholderBanner() {
		BufferedImage i = new BufferedImage(PANEL_WIDTH, 62, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = i.createGraphics();
		g.setColor(new Color(22, 31, 43));
		g.fillRect(0, 0, i.getWidth(), i.getHeight());
		g.setColor(GOLD);
		g.setFont(new Font("SansSerif", Font.BOLD, 24));
		g.drawString("THE ANCHOR", 38, 39);
		g.dispose();
		return i;
	}

	private static ImageIcon loadBannerIcon(boolean animated) {
		java.net.URL resource = AnchorPanel.class.getResource(animated ? "/anchor_banner.gif" : "/anchor_banner.png");
		return resource == null ? new ImageIcon(scale(placeholderBanner(), PANEL_WIDTH, 62)) : new ImageIcon(resource);
	}

	private static final class AspectRatioIconLabel extends JLabel {
		private int aspectHeight = -1;
		private final int heightLimit;

		private AspectRatioIconLabel() { this(Integer.MAX_VALUE); }

		private AspectRatioIconLabel(int heightLimit) {
			this.heightLimit = heightLimit;
			if (heightLimit < Integer.MAX_VALUE) setPreferredSize(new Dimension(PANEL_WIDTH, heightLimit));
			setOpaque(true);
			setBackground(AnchorTheme.BACKGROUND);
			addComponentListener(new ComponentAdapter() {
				@Override
				public void componentResized(ComponentEvent event) {
					refreshAspectRatio();
				}
			});
		}

		private void refreshAspectRatio() {
			Icon icon = getIcon();
			int width = getWidth();
			if (width <= 0 || icon == null || icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0)
				return;
			int height = Math.min(heightLimit, Math.max(1, (int) Math.round((double) width * icon.getIconHeight() / icon.getIconWidth())));
			if (height == aspectHeight)
				return;
			aspectHeight = height;
			setMinimumSize(new Dimension(0, height));
			setPreferredSize(new Dimension(icon.getIconWidth(), height));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
			if (getParent() != null)
				getParent().revalidate();
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Icon icon = getIcon();
			if (icon == null || icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0)
				return;
			Graphics2D g = (Graphics2D) graphics.create();
			g.setColor(getBackground());
			g.fillRect(0, 0, getWidth(), getHeight());
			double scale = Math.max((double) getWidth() / icon.getIconWidth(), (double) getHeight() / icon.getIconHeight());
			g.translate((getWidth() - icon.getIconWidth() * scale) / 2, getHeight() - icon.getIconHeight() * scale);
			g.scale(scale, scale);
			icon.paintIcon(this, g, 0, 0);
			g.dispose();
		}

		@Override
		public boolean imageUpdate(Image image, int flags, int x, int y, int width, int height) {
			boolean updating = super.imageUpdate(image, flags, x, y, width, height);
			if ((flags & (FRAMEBITS | ALLBITS | SOMEBITS)) != 0)
				repaint();
			return updating;
		}
	}

	private static final class EvidenceImagePanel extends JPanel {
		private final BufferedImage image;

		private EvidenceImagePanel(BufferedImage image) {
			this.image = image;
			setBackground(Color.BLACK);
			setPreferredSize(new Dimension(Math.min(900, image.getWidth()), Math.min(700, image.getHeight())));
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Insets insets = getInsets();
			int availableWidth = Math.max(1, getWidth() - insets.left - insets.right);
			int availableHeight = Math.max(1, getHeight() - insets.top - insets.bottom);
			double factor = Math.min((double) availableWidth / image.getWidth(),
					(double) availableHeight / image.getHeight());
			int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
			int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
			int x = insets.left + (availableWidth - width) / 2;
			int y = insets.top + (availableHeight - height) / 2;
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(image, x, y, width, height, null);
			g.dispose();
		}
	}

	private static BufferedImage placeholderAvatar() {
		BufferedImage i = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = i.createGraphics();
		g.setColor(new Color(40, 52, 68));
		g.fillRect(0, 0, 32, 32);
		g.setColor(GOLD);
		g.setFont(new Font("SansSerif", Font.BOLD, 28));
		g.drawString("A", 20, 39);
		g.dispose();
		return i;
	}

	private static BufferedImage placeholderCompetition(String kind) {
		BufferedImage i = new BufferedImage(PANEL_WIDTH - 32, 76, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = i.createGraphics();
		g.setColor(new Color(40, 52, 68));
		g.fillRect(0, 0, i.getWidth(), i.getHeight());
		g.setColor(GOLD);
		g.setFont(new Font("SansSerif", Font.BOLD, 20));
		g.drawString(kind, 12, 45);
		g.dispose();
		return i;
	}
}
