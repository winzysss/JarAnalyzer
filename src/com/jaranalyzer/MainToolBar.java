package com.jaranalyzer;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;

public class MainToolBar extends JToolBar {

	private JButton openBtn, saveBtn, saveAllBtn, closeBtn;
	private JComboBox<String> languageCombo;
	private MainWindow mainWindow;

	public MainToolBar(MainWindow mainWindow) {
		this.mainWindow = mainWindow;
		setFloatable(false);
		setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));

		openBtn = createButton(LanguageManager.getString("toolbar.open"), LanguageManager.getString("tooltip.open"));
		openBtn.addActionListener(e -> mainWindow.openFile());
		add(openBtn);

		saveBtn = createButton(LanguageManager.getString("toolbar.save"), LanguageManager.getString("tooltip.save"));
		saveBtn.addActionListener(e -> mainWindow.saveFile());
		add(saveBtn);

		saveAllBtn = createButton(LanguageManager.getString("toolbar.saveAll"), LanguageManager.getString("tooltip.saveAll"));
		saveAllBtn.addActionListener(e -> mainWindow.saveAllFiles());
		add(saveAllBtn);

		addSeparator();

		closeBtn = createButton(LanguageManager.getString("toolbar.close"), LanguageManager.getString("tooltip.close"));
		closeBtn.addActionListener(e -> mainWindow.closeTab());
		add(closeBtn);

		add(Box.createHorizontalStrut(20));

		JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		rightPanel.setOpaque(false);

		JLabel langLabel = new JLabel(LanguageManager.getString("toolbar.language") + ":");
		langLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
		rightPanel.add(langLabel);

		languageCombo = new JComboBox<>(new String[]{"Türkçe", "English"});
		// 90 px clipped "Türkçe" to "Tür..." once the dark theme's font was in
		// place; the arrow button eats more of the track than the old look did.
		languageCombo.setPreferredSize(new Dimension(120, 28));
		languageCombo.setMinimumSize(new Dimension(120, 28));
		languageCombo.setToolTipText(LanguageManager.getString("tooltip.language"));
		// Sync with the language MainWindow already loaded from preferences, and
		// do it before the listener exists so this does not count as a change.
		// Without it the box always opened reading "Türkçe" while the rest of the
		// window was in whatever language was actually saved.
		languageCombo.setSelectedIndex(
				LanguageManager.getCurrentLanguage() == LanguageManager.Language.TR ? 0 : 1);
		languageCombo.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (e.getActionCommand().equals("comboBoxChanged")) return;
				int sel = languageCombo.getSelectedIndex();
				LanguageManager.Language lang = (sel == 0) ? LanguageManager.Language.TR : LanguageManager.Language.EN;
				LanguageManager.setLanguage(lang);
				ConfigSaver.getLoadedInstance().getAppPreferences().setLanguage(lang.getCode());
				mainWindow.updateAllLanguageTexts();
			}
		});
		languageCombo.setActionCommand("langCombo");
		rightPanel.add(languageCombo);

		add(Box.createHorizontalGlue());
		add(rightPanel);
	}

	private JButton createButton(String text, String tooltip) {
		JButton btn = new JButton(text);
		btn.setToolTipText(tooltip);
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setContentAreaFilled(false);
		btn.setOpaque(false);
		btn.setFont(new Font("Segoe UI", Font.PLAIN, 13));
		btn.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
		return btn;
	}

	public void updateLanguage() {
		openBtn.setText(LanguageManager.getString("toolbar.open"));
		saveBtn.setText(LanguageManager.getString("toolbar.save"));
		saveAllBtn.setText(LanguageManager.getString("toolbar.saveAll"));
		closeBtn.setText(LanguageManager.getString("toolbar.close"));

		openBtn.setToolTipText(LanguageManager.getString("tooltip.open"));
		saveBtn.setToolTipText(LanguageManager.getString("tooltip.save"));
		saveAllBtn.setToolTipText(LanguageManager.getString("tooltip.saveAll"));
		closeBtn.setToolTipText(LanguageManager.getString("tooltip.close"));

		int langSel = (LanguageManager.getCurrentLanguage() == LanguageManager.Language.TR) ? 0 : 1;
		languageCombo.setSelectedIndex(langSel);
	}
}
