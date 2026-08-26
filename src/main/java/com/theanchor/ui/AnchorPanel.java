/*
 * Portions of this panel are adapted from the PB Tracker Sync, Llama Club, and
 * Terpinheimer plugin panels. See LICENSES/pb-tracker-sync-LICENSE.txt,
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
import javax.imageio.ImageIO;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

public class AnchorPanel extends PluginPanel {
	private static final Color GOLD = new Color(207, 164, 76);
	private static final Color TAB_BG = new Color(30, 33, 36);
	private static final Color TAB_ACTIVE_BG = new Color(207, 164, 76);
	private static final Color TAB_ACTIVE_FG = new Color(20, 20, 20);
	private static final Color TAB_INACTIVE_FG = new Color(170, 170, 170);
	private static final Color TAB_HOVER_BG = new Color(55, 60, 65);
	private static final Color CONNECTED_COLOR = new Color(76, 175, 80);
	private static final Color DISCONNECTED_COLOR = new Color(158, 158, 158);
	private static final Color BOTW_ACCENT = new Color(176, 70, 67);
	private static final Color SOTW_ACCENT = new Color(55, 142, 157);
	private static final Color PVM_ACCENT = new Color(79, 135, 201);
	private static final Color CLAN_ACCENT = new Color(67, 164, 142);
	private static final Color SCORE_COLOR = new Color(238, 205, 124);
	private static final int NAV_BUTTON_HEIGHT = 27;
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
	private final JLabel banner = new AspectRatioIconLabel();
	private final JLabel avatar = new JLabel();
	private final JLabel identity = new JLabel("Log in to RuneLite", SwingConstants.LEFT);
	private final JLabel status = muted("");
	private final JLabel connectionDot = new JLabel();
	private final JPanel home = verticalPanel();
	private final JPanel submissions = verticalPanel();
	private final CardLayout contentCards = new CardLayout();
	private final JPanel contentPanel = new JPanel(contentCards);
	private JLabel homeTab;
	private JLabel submissionsTab;
	private final JPanel extraSettings = verticalPanel();
	private JButton extraSettingsButton;
	private boolean extraSettingsVisible;
	private boolean botwExpanded = true;
	private boolean sotwExpanded = true;
	private String activeTab = "Home";

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
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(null);

		JPanel header = new JPanel(new GridBagLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints headerRow = new GridBagConstraints();
		headerRow.gridx = 0;
		headerRow.gridy = 0;
		headerRow.weightx = 1.0;
		headerRow.fill = GridBagConstraints.HORIZONTAL;
		headerRow.anchor = GridBagConstraints.NORTHWEST;
		banner.setAlignmentX(Component.CENTER_ALIGNMENT);
		refreshBanner();
		header.add(banner, headerRow);

		// Profile bar with connection status dot
		JPanel profile = new JPanel(new BorderLayout(8, 0));
		profile.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		profile.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(67, 70, 73)),
				BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		avatar.setPreferredSize(new Dimension(58, 58));
		avatar.setIcon(new ImageIcon(scale(placeholderAvatar(), 58, 58)));
		profile.add(avatar, BorderLayout.WEST);

		JPanel text = verticalPanel();
		identity.setForeground(Color.WHITE);
		identity.setFont(identity.getFont().deriveFont(Font.BOLD));
		text.add(identity);
		text.add(status);
		profile.add(text, BorderLayout.CENTER);

		connectionDot.setPreferredSize(new Dimension(14, 14));
		connectionDot.setToolTipText("Not connected");
		connectionDot.setHorizontalAlignment(SwingConstants.CENTER);
		connectionDot.setVerticalAlignment(SwingConstants.CENTER);
		profile.add(connectionDot, BorderLayout.EAST);

		headerRow.gridy++;
		header.add(profile, headerRow);

		// Navigation: two tabs on the first row, one full-width settings row beneath
		// them.
		JPanel navigation = fullWidthVerticalPanel();
		navigation.setLayout(new GridBagLayout());
		navigation.setBackground(ColorScheme.DARK_GRAY_COLOR);
		navigation.setAlignmentX(Component.LEFT_ALIGNMENT);
		GridBagConstraints navigationRow = new GridBagConstraints();
		navigationRow.gridx = 0;
		navigationRow.gridy = 0;
		navigationRow.weightx = 1.0;
		navigationRow.fill = GridBagConstraints.HORIZONTAL;
		navigationRow.anchor = GridBagConstraints.NORTHWEST;
		navigation.add(buildTabBar(), navigationRow);
		navigationRow.gridy++;
		navigation.add(buildExtraSettings(), navigationRow);
		headerRow.gridy++;
		header.add(navigation, headerRow);

		// Content area with CardLayout
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.add(scroll(home), "Home");
		contentPanel.add(scroll(submissions), "Submissions");
		contentCards.show(contentPanel, "Home");

		add(header, BorderLayout.NORTH);
		add(contentPanel, BorderLayout.CENTER);
		data.addListener(dataListener);
		pipeline.addListener(dataListener);
		refresh();
	}

	private JPanel buildExtraSettings() {
		JPanel container = fullWidthVerticalPanel();
		container.setAlignmentX(Component.LEFT_ALIGNMENT);
		container.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
		extraSettingsButton = actionButton("Extra Settings", false);
		extraSettingsButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		extraSettingsButton.setMinimumSize(new Dimension(0, NAV_BUTTON_HEIGHT));
		extraSettingsButton.setPreferredSize(new Dimension(0, NAV_BUTTON_HEIGHT));
		extraSettingsButton
				.setMaximumSize(new Dimension(Integer.MAX_VALUE, extraSettingsButton.getPreferredSize().height));
		extraSettingsButton.addActionListener(e -> toggleExtraSettings());
		container.add(extraSettingsButton);

		extraSettings.setAlignmentX(Component.LEFT_ALIGNMENT);
		extraSettings.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		extraSettings.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		extraSettings.setVisible(false);
		extraSettings.add(sectionHeading("ACTIONS"));
		extraSettings.add(Box.createVerticalStrut(4));
		JPanel actions = new JPanel(new GridLayout(0, 2, 6, 4));
		actions.setOpaque(false);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.add(configActionButton("Refresh profile", "refreshProfile", true));
		actions.add(configActionButton("Retry uploads", "retryOutbox", true));
		JButton clearCache = configActionButton("Clear image cache", "clearImageCache", false);
		actions.add(clearCache);
		actions.add(Box.createGlue());
		actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, actions.getPreferredSize().height));
		extraSettings.add(actions);
		container.add(extraSettings);
		return container;
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

	private void toggleExtraSettings() {
		extraSettingsVisible = !extraSettingsVisible;
		extraSettings.setVisible(extraSettingsVisible);
		extraSettingsButton.setText(extraSettingsVisible ? "Hide Extra Settings" : "Extra Settings");
		revalidate();
		repaint();
	}

	private JPanel buildTabBar() {
		JPanel bar = new JPanel(new GridLayout(1, 2, 2, 0)) {
			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(TAB_BG);
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
				g2.dispose();
			}
		};
		bar.setOpaque(false);
		bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		int tabBarHeight = NAV_BUTTON_HEIGHT + 12;
		bar.setMinimumSize(new Dimension(0, tabBarHeight));
		bar.setPreferredSize(new Dimension(0, tabBarHeight));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, tabBarHeight));

		homeTab = createTabLabel("Home", true);
		submissionsTab = createTabLabel("Submissions", false);

		homeTab.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				switchTab("Home");
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				if (!activeTab.equals("Home"))
					homeTab.setBackground(TAB_HOVER_BG);
				homeTab.repaint();
			}

			@Override
			public void mouseExited(MouseEvent e) {
				if (!activeTab.equals("Home"))
					homeTab.setBackground(TAB_BG);
				homeTab.repaint();
			}
		});
		submissionsTab.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				switchTab("Submissions");
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				if (!activeTab.equals("Submissions"))
					submissionsTab.setBackground(TAB_HOVER_BG);
				submissionsTab.repaint();
			}

			@Override
			public void mouseExited(MouseEvent e) {
				if (!activeTab.equals("Submissions"))
					submissionsTab.setBackground(TAB_BG);
				submissionsTab.repaint();
			}
		});

		bar.add(homeTab);
		bar.add(submissionsTab);
		return bar;
	}

	private static JLabel createTabLabel(String text, boolean active) {
		JLabel label = new JLabel(text, SwingConstants.CENTER) {
			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(getBackground());
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		label.setOpaque(false);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
		label.setMinimumSize(new Dimension(0, NAV_BUTTON_HEIGHT));
		label.setPreferredSize(new Dimension(0, NAV_BUTTON_HEIGHT));
		label.setMaximumSize(new Dimension(Integer.MAX_VALUE, NAV_BUTTON_HEIGHT));
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		if (active) {
			label.setBackground(TAB_ACTIVE_BG);
			label.setForeground(TAB_ACTIVE_FG);
		} else {
			label.setBackground(TAB_BG);
			label.setForeground(TAB_INACTIVE_FG);
		}
		return label;
	}

	private void switchTab(String tabName) {
		if (activeTab.equals(tabName))
			return;
		activeTab = tabName;

		homeTab.setBackground(tabName.equals("Home") ? TAB_ACTIVE_BG : TAB_BG);
		homeTab.setForeground(tabName.equals("Home") ? TAB_ACTIVE_FG : TAB_INACTIVE_FG);
		submissionsTab.setBackground(tabName.equals("Submissions") ? TAB_ACTIVE_BG : TAB_BG);
		submissionsTab.setForeground(tabName.equals("Submissions") ? TAB_ACTIVE_FG : TAB_INACTIVE_FG);

		homeTab.repaint();
		submissionsTab.repaint();
		contentCards.show(contentPanel, tabName);
	}

	private void refreshOnEdt() {
		SwingUtilities.invokeLater(this::refresh);
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
			status.setText(html(data.message()));
			avatar.setIcon(new ImageIcon(scale(placeholderAvatar(), 58, 58)));
		} else {
			String rank = profile.standings == null ? "—" : value(profile.standings.currentRank);
			identity.setText(html("<b>" + esc(profile.member.name) + "</b><br><span style='color:#eecd7c'>"
					+ esc(rank) + "</span>"));
			String memberStatus = value(profile.member.rosterRank);
			if (profile.member.status != null && !profile.member.status.isBlank()) {
				memberStatus += " · " + profile.member.status;
			}
			status.setText(html("<span style='color:#9da1a4'>" + esc(memberStatus) + "</span>"));
			avatar.setIcon(new ImageIcon(
					scale(data.profileImage() == null ? placeholderAvatar() : data.profileImage(), 58, 58)));
		}
		updateConnectionDot();
		rebuildHome();
		rebuildSubmissions();
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
		AnchorModels.Profile profile = data.profile();
		if (profile != null && profile.standings != null) {
			AnchorModels.Standings s = profile.standings;
			JPanel ranks = card();
			ranks.add(sectionHeading("CLAN PROFILE"));
			ranks.add(Box.createVerticalStrut(5));
			ranks.add(statRow("PVM RANK", rank(s.pvmRank), PVM_ACCENT,
					"CLAN RANK", rank(s.clanRank), CLAN_ACCENT));
			ranks.add(Box.createVerticalStrut(4));
			ranks.add(statRow("PVM POINTS", number(s.pvmPoints), PVM_ACCENT,
					"CLAN POINTS", number(s.clanPoints), CLAN_ACCENT));
			if (s.nextRank != null) {
				ranks.add(Box.createVerticalStrut(7));
				ranks.add(nextRank(value(s.nextRank.name)));
				ranks.add(Box.createVerticalStrut(4));
				ranks.add(progress("PvM", s.pvmPoints, s.nextRank.pvmPointsRemaining, PVM_ACCENT));
				ranks.add(Box.createVerticalStrut(3));
				ranks.add(progress("Clan", s.clanPoints, s.nextRank.clanPointsRemaining, CLAN_ACCENT));
			} else
				ranks.add(muted("Administrative or highest rank"));
			home.add(ranks);
			home.add(Box.createVerticalStrut(8));
		}
		AnchorModels.CompetitionPanels competitions = data.competitionPanels();
		String weeklyTiming = sharedCompetitionTiming(competitions);
		if (!weeklyTiming.isEmpty()) {
			home.add(weeklyTimer(weeklyTiming));
			home.add(Box.createVerticalStrut(8));
		}
		AnchorModels.Standings standings = profile == null ? null : profile.standings;
		String playerName = profile == null || profile.member == null ? null : profile.member.name;
		boolean authRequired = requiresCompetitionAuthentication(data.connection());
		home.add(competition("BOTW", competitions == null ? null : competitions.botw,
				authRequired ? data.unauthenticatedBotwImage() : data.botwImage(),
				standings == null ? null : standings.botwRank, playerName, botwExpanded));
		home.add(Box.createVerticalStrut(8));
		home.add(competition("SOTW", competitions == null ? null : competitions.sotw,
				authRequired ? data.unauthenticatedSotwImage() : data.sotwImage(),
				standings == null ? null : standings.sotwRank, playerName, sotwExpanded));
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

		int cardContentWidth = PANEL_WIDTH - 32;
		BufferedImage scaledArtwork = scaleToWidth(image == null ? placeholderCompetition(kind) : image,
				cardContentWidth);
		JLabel artwork = new AspectRatioIconLabel();
		artwork.setIcon(new ImageIcon(scaledArtwork));
		artwork.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(artwork);
		card.add(Box.createVerticalStrut(5));
		boolean upcoming = isUpcoming(competition);
		boolean finished = isFinished(competition);
		if (competition == null) {
			card.add(muted("Competition data is currently unavailable"));
			return card;
		}
		if (upcoming) {
			Color accent = "BOTW".equals(kind) ? BOTW_ACCENT : SOTW_ACCENT;
			card.add(competitionSectionHeading("UPCOMING", accent));
			card.add(Box.createVerticalStrut(3));
			card.add(muted(startsCountdown(competition.startsAt)));
			return card;
		}
		List<AnchorModels.PanelLeader> leaders = competition.leaders == null ? java.util.Collections.emptyList()
				: competition.leaders;
		long leadingScore = leaders.stream().mapToLong(leader -> Math.max(0, leader.gained)).max().orElse(0);
		Color accent = "BOTW".equals(kind) ? BOTW_ACCENT : SOTW_ACCENT;
		boolean showPersonalProgress = !finished
				|| (playerRank != null && playerRank.rank != null && playerRank.rank <= 3);
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

	private void toggleCompetition(String kind) {
		if ("BOTW".equals(kind))
			botwExpanded = !botwExpanded;
		else
			sotwExpanded = !sotwExpanded;
		rebuildHome();
		home.revalidate();
		home.repaint();
	}

	private void rebuildSubmissions() {
		submissions.removeAll();
		List<EvidenceStore.Record> records = evidence.records();
		if (records.isEmpty()) {
			submissions.add(muted(
					"Drops, personal bests, collection log entries, pets, and combat achievements will appear here."));
			return;
		}
		for (EvidenceStore.Record record : groupedRecords(records)) {
			List<EvidenceStore.Record> group = pipeline.groupRecords(record);
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
			// PB records may have legacy party metadata, but party splits only apply to
			// qualifying loot.
			if (record.metadata.party != null && !isPersonalBest(record)) {
				card.add(Box.createVerticalStrut(4));
				card.add(fieldRow("Party size", party));
				card.add(fieldRow("Clan members", clanMembers));
				card.add(fieldRow("Non-clan members", nonClanMembers));
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
				retry.addActionListener(e -> pipeline.retryGroup(record));
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
					pipeline.updateAndSubmitGroup(record, partySize, clanCount, nonClanCount, notes.getText());
				});
				actionButtons.add(submit);
			}
			JButton discard = actionButton("Discard", false);
			discard.addActionListener(e -> {
				if (JOptionPane.showConfirmDialog(this, "Remove this local evidence?", "Discard",
						JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
					for (EvidenceStore.Record member : group) evidence.deleteLocal(member.metadata.eventId);
					refresh();
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

	private static List<EvidenceStore.Record> groupedRecords(List<EvidenceStore.Record> records) {
		java.util.Map<String, List<EvidenceStore.Record>> groups = new java.util.LinkedHashMap<>();
		for (EvidenceStore.Record record : records) {
			String groupId = EventPipeline.submissionGroupId(record);
			String key = groupId == null ? "event:" + record.metadata.eventId : "group:" + groupId;
			groups.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(record);
		}
		List<EvidenceStore.Record> result = new java.util.ArrayList<>();
		for (List<EvidenceStore.Record> group : groups.values()) result.add(preferredRecord(group));
		return result;
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
		if (party == null || party.members == null || party.members.isEmpty())
			return "";
		StringBuilder names = new StringBuilder("Party: ");
		for (AnchorModels.PartyMember member : party.members) {
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

	private JPanel submissionHeading(EvidenceStore.Record record, AnchorModels.EventStatus displayStatus,
			List<EvidenceStore.Record> group) {
		JPanel heading = new JPanel(new BorderLayout(7, 0));
		heading.setOpaque(false);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel text = verticalPanel();
		text.setOpaque(false);
		text.add(sectionHeading(eventLabel(record, group)));
		text.add(Box.createVerticalStrut(2));
		JLabel title = new JLabel(title(record));
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD));
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

	private static JButton actionButton(String text, boolean primary) {
		JButton button = new JButton(text);
		button.setForeground(primary ? TAB_ACTIVE_FG : new Color(205, 208, 211));
		button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
		button.setFocusPainted(false);
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setMargin(new Insets(0, 8, 0, 8));
		button.setPreferredSize(new Dimension(0, 27));
		button.setBackground(primary ? GOLD : new Color(47, 51, 55));
		button.setRolloverEnabled(true);
		button.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
			@Override
			public void paint(Graphics graphics, javax.swing.JComponent component) {
				JButton target = (JButton) component;
				Graphics2D g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				Color background = target.getBackground();
				if (target.getModel().isPressed())
					background = background.darker();
				else if (target.getModel().isRollover())
					background = primary ? new Color(224, 181, 91) : TAB_HOVER_BG;
				g.setColor(background);
				g.fillRoundRect(0, 0, target.getWidth(), target.getHeight(), 8, 8);
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
		badge.setFont(badge.getFont().deriveFont(Font.BOLD, 10f));
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
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
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
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return panel;
	}

	private static JPanel card() {
		JPanel panel = new JPanel() {
			@Override
			public Dimension getMaximumSize() {
				return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
			}
		};
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	static JScrollPane scroll(JPanel panel) {
		ViewportWidthPanel viewport = new ViewportWidthPanel();
		viewport.add(panel, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(viewport);
		scroll.setBorder(null);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setOpaque(true);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	private static final class ViewportWidthPanel extends JPanel implements Scrollable {
		private ViewportWidthPanel() {
			super(new BorderLayout());
			setBackground(ColorScheme.DARK_GRAY_COLOR);
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
			return getParent() instanceof JViewport && getPreferredSize().height < getParent().getHeight();
		}
	}

	private static JLabel label(String text, boolean bold) {
		JLabel label = new JLabel(html(esc(text)));
		label.setForeground(Color.WHITE);
		if (bold)
			label.setFont(label.getFont().deriveFont(Font.BOLD));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setMaximumSize(new Dimension(PANEL_WIDTH, 40));
		return label;
	}

	private static JLabel muted(String text) {
		JLabel label = new JLabel(html("<span style='color:#aaaaaa'>" + esc(text) + "</span>"));
		label.setForeground(Color.LIGHT_GRAY);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setMaximumSize(new Dimension(PANEL_WIDTH, 40));
		return label;
	}

	private static JLabel wrappedMuted(String text) {
		JLabel label = muted(text);
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
		heading.setFont(heading.getFont().deriveFont(Font.BOLD, 12f));
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		return heading;
	}

	private static JLabel competitionSectionHeading(String text, Color color) {
		JLabel heading = new JLabel(text);
		heading.setForeground(color);
		heading.setFont(heading.getFont().deriveFont(Font.BOLD, 11f));
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		return heading;
	}

	private static JPanel statRow(String leftLabel, String leftValue, Color leftColor,
			String rightLabel, String rightValue, Color rightColor) {
		JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(PANEL_WIDTH, 34));
		row.add(stat(leftLabel, leftValue, leftColor));
		row.add(stat(rightLabel, rightValue, rightColor));
		return row;
	}

	private static JPanel stat(String label, String value, Color color) {
		JPanel stat = new JPanel(new BorderLayout(0, 1));
		stat.setOpaque(false);
		JLabel name = new JLabel(label);
		name.setForeground(new Color(145, 148, 151));
		name.setFont(name.getFont().deriveFont(Font.BOLD, 11f));
		JLabel amount = new JLabel(value);
		amount.setForeground(color);
		amount.setFont(amount.getFont().deriveFont(Font.BOLD, 14f));
		stat.add(name, BorderLayout.NORTH);
		stat.add(amount, BorderLayout.CENTER);
		return stat;
	}

	private static JPanel nextRank(String rankName) {
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(PANEL_WIDTH, 22));
		JLabel label = sectionHeading("NEXT RANK");
		JLabel value = new JLabel(rankName, SwingConstants.RIGHT);
		value.setForeground(SCORE_COLOR);
		value.setFont(value.getFont().deriveFont(Font.BOLD, 13f));
		row.add(label, BorderLayout.WEST);
		row.add(value, BorderLayout.EAST);
		return row;
	}

	private static JPanel weeklyTimer(String timing) {
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setBackground(new Color(38, 41, 44));
		p.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 3, 0, 0, GOLD),
				BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(PANEL_WIDTH, 34));
		JLabel title = new JLabel("WEEKLY EVENTS");
		title.setForeground(new Color(155, 158, 161));
		title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
		JLabel value = new JLabel(timing.toUpperCase(Locale.ROOT), SwingConstants.RIGHT);
		value.setForeground(SCORE_COLOR);
		value.setFont(value.getFont().deriveFont(Font.BOLD, 12f));
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
		rankLabel.setFont(rankLabel.getFont().deriveFont(Font.BOLD, 12f));
		rankLabel.setPreferredSize(new Dimension(18, 18));
		JLabel nameLabel = new JLabel(name);
		nameLabel.setForeground(new Color(225, 225, 225));
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
		nameLabel.setToolTipText(name);
		JLabel scoreLabel = new JLabel(styledScore(score), SwingConstants.RIGHT);
		scoreLabel.setForeground(SCORE_COLOR);
		scoreLabel.setFont(scoreLabel.getFont().deriveFont(Font.BOLD, 12f));
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
		JPanel p = verticalPanel();
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(PANEL_WIDTH, 40));
		long target = Math.max(1, current + remaining);
		JProgressBar bar = new JProgressBar(0, 1000);
		bar.setValue((int) Math.min(1000, current * 1000 / target));
		bar.setStringPainted(true);
		bar.setString(name + ": " + number(current) + " / " + number(target));
		bar.setForeground(accent);
		bar.setBackground(new Color(43, 46, 49));
		bar.setBorder(BorderFactory.createEmptyBorder());
		bar.setMaximumSize(new Dimension(PANEL_WIDTH, 21));
		bar.setFont(bar.getFont().deriveFont(Font.BOLD, 11f));
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(bar);
		p.add(muted(number(remaining) + " remaining"));
		return p;
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
		if (record.metadata.context != null) {
			Object values = record.metadata.context.get("submissionTypes");
			if (values instanceof Iterable)
				for (Object value : (Iterable<?>) values) if (value != null) groupedTypes.add(String.valueOf(value));
		}
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
		if (!isPersonalBest(record)
				|| record.metadata.details == null)
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
		return "<html><div style='width:" + (PANEL_WIDTH - 60) + "px'>" + value + "</div></html>";
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

		private AspectRatioIconLabel() {
			setOpaque(true);
			setBackground(ColorScheme.DARK_GRAY_COLOR);
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
			int height = Math.max(1, (int) Math.round((double) width * icon.getIconHeight() / icon.getIconWidth()));
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
			g.scale((double) getWidth() / icon.getIconWidth(), (double) getHeight() / icon.getIconHeight());
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
		BufferedImage i = new BufferedImage(58, 58, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = i.createGraphics();
		g.setColor(new Color(40, 52, 68));
		g.fillRect(0, 0, 58, 58);
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
