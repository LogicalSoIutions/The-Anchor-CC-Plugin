/* Visual components adapted from Reval Clan. See LICENSES/reval-clan-LICENSE.txt. */
package com.theanchor.ui;

import java.awt.*;
import javax.swing.*;
import net.runelite.client.ui.FontManager;

/** Shared RuneLite-native visual system, following the Reval Clan panel. */
final class AnchorTheme {
	static final Color BACKGROUND = new Color(15, 17, 17);
	static final Color SURFACE = new Color(28, 31, 33);
	static final Color BORDER = new Color(50, 54, 58);
	static final Color TEXT = new Color(230, 230, 220);
	static final Color MUTED = new Color(150, 150, 140);
	static final Color GOLD = new Color(218, 165, 32);
	static final Color BLUE = new Color(66, 165, 245);
	static final Color GREEN = new Color(139, 195, 74);

	static Font font(int style, float size) {
		return style == Font.BOLD ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont();
	}

	static void typography(Component component) {
		Font font = component.getFont();
		if (font != null && !font.getName().equals(FontManager.getRunescapeBoldFont().getName())
				&& !font.getName().equals(FontManager.getRunescapeSmallFont().getName()))
			component.setFont(font(font.getStyle(), font.getSize()));
		if (component instanceof Container)
			for (Component child : ((Container) component).getComponents()) typography(child);
	}

	/** Style both the closed control and its popup, independently of the host look and feel. */
	static void styleComboBox(JComboBox<?> combo) {
		combo.setFont(font(Font.PLAIN, 11));
		combo.setBackground(SURFACE);
		combo.setForeground(TEXT);
		combo.setFocusable(true);
		combo.setBorder(BorderFactory.createLineBorder(BORDER));
		combo.setPreferredSize(new Dimension(120, 28));
		combo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		combo.setRenderer(new DefaultListCellRenderer() {
			@Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
				super.getListCellRendererComponent(list, value, index, selected, focus);
				putClientProperty("html.disable", Boolean.TRUE);
				setFont(font(Font.PLAIN, 11));
				setBackground(selected && index >= 0 ? new Color(37, 63, 80) : SURFACE);
				setForeground(combo.isEnabled() ? selected ? Color.WHITE : TEXT : MUTED);
				setBorder(BorderFactory.createEmptyBorder(index < 0 ? 2 : 6, 8, index < 0 ? 2 : 6, 6));
				return this;
			}
		});
		combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
			@Override protected JButton createArrowButton() {
				JButton button = new JButton() {
					@Override protected void paintComponent(Graphics graphics) {
						Graphics2D g = (Graphics2D) graphics.create();
						g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
						g.setColor(getModel().isRollover() ? new Color(35, 38, 42) : SURFACE);
						g.fillRect(0, 0, getWidth(), getHeight());
						g.setColor(combo.isEnabled() ? GOLD : MUTED);
						int x = getWidth() / 2, y = getHeight() / 2;
						g.fillPolygon(new int[]{x - 4, x + 4, x}, new int[]{y - 2, y - 2, y + 2}, 3);
						g.dispose();
					}
				};
				button.setBorder(BorderFactory.createEmptyBorder());
				button.setFocusPainted(false);
				button.setContentAreaFilled(false);
				button.setRolloverEnabled(true);
				button.setPreferredSize(new Dimension(24, 26));
				return button;
			}
			@Override public void paintCurrentValueBackground(Graphics graphics, Rectangle bounds, boolean focus) {
				graphics.setColor(SURFACE);
				graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
			}
			@Override protected javax.swing.plaf.basic.ComboPopup createPopup() {
				return new javax.swing.plaf.basic.BasicComboPopup(comboBox) {
					{
						setBorder(BorderFactory.createLineBorder(BORDER));
						setBackground(SURFACE);
						getList().setBackground(SURFACE);
						getList().setForeground(TEXT);
						getList().setFont(font(Font.PLAIN, 11));
						getList().setSelectionBackground(new Color(37, 63, 80));
						scroller.setBorder(BorderFactory.createEmptyBorder());
						scroller.getViewport().setBackground(SURFACE);
					}
				};
			}
		});
	}

	static JPanel surface() { return surface(null); }

	static JPanel surface(Color accent) {
		JPanel panel = new JPanel() {
			@Override protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(Boolean.TRUE.equals(getClientProperty("hover")) ? new Color(35, 38, 42)
					: getClientProperty("fill") instanceof Color ? (Color) getClientProperty("fill") : SURFACE);
				g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
				if (accent != null) {
					g.setColor(accent);
					g.fillRoundRect(0, 8, 3, Math.max(0, getHeight() - 16), 3, 3);
				}
				g.dispose();
			}
			@Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
		};
		panel.setOpaque(false);
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		return panel;
	}

	static JPanel bar(long current, long total, Color color) {
		double fraction = total <= 0 ? 0 : Math.max(0, Math.min(1, (double) current / total));
		JPanel bar = new JPanel() {
			@Override protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(BORDER);
				g.fillRoundRect(0, 0, getWidth(), 6, 6, 6);
				g.setColor(color);
				g.fillRoundRect(0, 0, (int) Math.round(getWidth() * fraction), 6, 6, 6);
				g.dispose();
			}
		};
		bar.setOpaque(false);
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setPreferredSize(new Dimension(100, 6));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
		return bar;
	}
}
