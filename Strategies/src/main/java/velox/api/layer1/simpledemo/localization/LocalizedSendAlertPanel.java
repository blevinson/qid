package velox.api.layer1.simpledemo.localization;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import velox.api.layer1.common.Log;
import velox.api.layer1.gui.Layer1DefaultAlertIcons;
import velox.api.layer1.layers.utils.SoundSynthHelper;
import velox.api.layer1.messages.Layer1ApiAlertSettingsMessage;
import velox.api.layer1.messages.Layer1ApiSoundAlertDeclarationMessage;
import velox.api.layer1.messages.Layer1ApiSoundAlertMessage;
import velox.gui.StrategyPanel;
import velox.gui.utils.localization.LocalizedBundle;

class LocalizedSendAlertPanel extends StrategyPanel {
    
    private final JComboBox<String> comboBoxAliases;
    private final JComboBox<SeverityIconItem> combBoxAlertIcons;
    private final JComboBox<AlertDeclarationComboBoxOption> comboBoxAlertDeclarations;
    private final JSpinner prioritySpinner = new JSpinner();
    private final JSpinner repeatsSpinner = new JSpinner();
    private final JSpinner delaySpinner = new JSpinner();
    private final JTextField textFieldAlertMsg;
    private final JTextField textFieldAlertAdditionalInfo;
    private final JCheckBox chckbxPopup;
    private final JCheckBox chckbxSound;
    
    private final String sourceGlobal;
    
    private final Map<String, Layer1ApiAlertSettingsMessage> settingsPerDeclarationId = new ConcurrentHashMap<>();
    
    private final LocalizedBundle localizedBundle;
    
    interface SendAlertPanelCallback {
        void sendCustomAlert(Layer1ApiSoundAlertMessage message);
    }
    
    enum SeverityIcons {
        NONE("LocalizedSendAlertPanel.Enum.None", null),
        INFO("LocalizedSendAlertPanel.Enum.Info", Layer1DefaultAlertIcons.getInfoIcon()),
        WARN("LocalizedSendAlertPanel.Enum.Warn", Layer1DefaultAlertIcons.getWarnIcon()),
        ERROR("LocalizedSendAlertPanel.Enum.Error", Layer1DefaultAlertIcons.getErrorIcon());
        
        public final String localizationKey;
        public final Image icon;
    
        SeverityIcons(String localizationKey, Image icon) {
            this.localizationKey = localizationKey;
            this.icon = icon;
        }
    }
    
    private class SeverityIconItem {
        public final SeverityIcons severityIcon;
        
        public SeverityIconItem(SeverityIcons severityIcon) {
            this.severityIcon = severityIcon;
        }
        
        @Override
        public String toString() {
            return localizedBundle.getString(severityIcon.localizationKey);
        }
    }

    public LocalizedSendAlertPanel(SendAlertPanelCallback callback, LocalizedBundle localizedBundle) {
        super(localizedBundle.getString("LocalizedSendAlertPanel.Title"));
        this.localizedBundle = localizedBundle;
        
        this.sourceGlobal = localizedBundle.getString("LocalizedSendAlertPanel.SourceGlobal");
        
        GridBagLayout gridBagLayout = new GridBagLayout();
        gridBagLayout.columnWidths = new int[]{150, 0, 0};
        gridBagLayout.rowHeights = new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        gridBagLayout.columnWeights = new double[]{0.0, 1.0, Double.MIN_VALUE};
        gridBagLayout.rowWeights = new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, Double.MIN_VALUE};
        setLayout(gridBagLayout);
        
        // This label is used to demonstrate how to use localized text with variables formatted in the text.
        // It is needed, since different languages may have different sentence structures, and such variables
        // may need to be placed differently (exact placement is specified in the localization file within the localization
        // string using "{variableName}" syntax).
        JLabel lblSampleLocalizedText = new JLabel(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.SampleLocalizedText",
                Map.of("xValue", 12345,
                        "yText", "Lorem ipsum")));
        GridBagConstraints gbc_lblSampleLocalizedText = new GridBagConstraints();
        gbc_lblSampleLocalizedText.insets = new Insets(0, 0, 5, 5);
        gbc_lblSampleLocalizedText.anchor = GridBagConstraints.CENTER;
        gbc_lblSampleLocalizedText.gridx = 0;
        gbc_lblSampleLocalizedText.gridy = 0;
        gbc_lblSampleLocalizedText.gridwidth = 2;
        gbc_lblSampleLocalizedText.fill = GridBagConstraints.HORIZONTAL;
        add(lblSampleLocalizedText, gbc_lblSampleLocalizedText);
        
        JLabel lblSource = new JLabel(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.SourceInstrument"));
        GridBagConstraints gbc_lblSource = new GridBagConstraints();
        gbc_lblSource.insets = new Insets(0, 0, 5, 5);
        gbc_lblSource.anchor = GridBagConstraints.EAST;
        gbc_lblSource.gridx = 0;
        gbc_lblSource.gridy = 1;
        add(lblSource, gbc_lblSource);
        
        comboBoxAliases = new JComboBox<>();
        comboBoxAliases.addItem(sourceGlobal);
        GridBagConstraints gbc_comboBoxSource = new GridBagConstraints();
        gbc_comboBoxSource.insets = new Insets(0, 0, 5, 0);
        gbc_comboBoxSource.fill = GridBagConstraints.HORIZONTAL;
        gbc_comboBoxSource.gridx = 1;
        gbc_comboBoxSource.gridy = 1;
        add(comboBoxAliases, gbc_comboBoxSource);
        
        JLabel repeatsLabel = new JLabel(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.AlertRepeats"));
        repeatsLabel.setToolTipText(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.AlertRepeats.Tooltip"));
        repeatsLabel.setHorizontalAlignment(SwingConstants.CENTER);
        GridBagConstraints gbc_repeatsLabel = new GridBagConstraints();
        gbc_repeatsLabel.anchor = GridBagConstraints.EAST;
        gbc_repeatsLabel.insets = new Insets(0, 0, 5, 5);
        gbc_repeatsLabel.gridx = 0;
        gbc_repeatsLabel.gridy = 2;
        add(repeatsLabel, gbc_repeatsLabel);

        repeatsSpinner.setModel(new SpinnerNumberModel(Long.valueOf(1), Long.valueOf(1), Long.valueOf(Long.MAX_VALUE), Long.valueOf(1)));
        GridBagConstraints gbc_repeatsSpinner = new GridBagConstraints();
        gbc_repeatsSpinner.fill = GridBagConstraints.HORIZONTAL;
        gbc_repeatsSpinner.insets = new Insets(0, 0, 5, 0);
        gbc_repeatsSpinner.gridx = 1;
        gbc_repeatsSpinner.gridy = 2;
        add(repeatsSpinner, gbc_repeatsSpinner);
        
        JLabel repeatDelayLabel = new JLabel(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.RepeatDelay"));
        GridBagConstraints gbc_repeatDelayLabel = new GridBagConstraints();
        gbc_repeatDelayLabel.anchor = GridBagConstraints.EAST;
        gbc_repeatDelayLabel.insets = new Insets(0, 0, 5, 5);
        gbc_repeatDelayLabel.gridx = 0;
        gbc_repeatDelayLabel.gridy = 3;
        add(repeatDelayLabel, gbc_repeatDelayLabel);
        repeatDelayLabel.setHorizontalAlignment(SwingConstants.CENTER);
        repeatDelayLabel.setToolTipText(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.RepeatDelay.Tooltip"));

        delaySpinner.setModel(new SpinnerNumberModel(Long.valueOf(0), Long.valueOf(0), Long.valueOf(Long.MAX_VALUE), Long.valueOf(1)));
        GridBagConstraints gbc_delaySpinner = new GridBagConstraints();
        gbc_delaySpinner.fill = GridBagConstraints.HORIZONTAL;
        gbc_delaySpinner.insets = new Insets(0, 0, 5, 0);
        gbc_delaySpinner.gridx = 1;
        gbc_delaySpinner.gridy = 3;
        add(delaySpinner, gbc_delaySpinner);
        
        JLabel priorityLabel = new JLabel(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.Priority"));
        GridBagConstraints gbc_priorityLabel = new GridBagConstraints();
        gbc_priorityLabel.anchor = GridBagConstraints.EAST;
        gbc_priorityLabel.insets = new Insets(0, 0, 5, 5);
        gbc_priorityLabel.gridx = 0;
        gbc_priorityLabel.gridy = 4;
        add(priorityLabel, gbc_priorityLabel);
        priorityLabel.setToolTipText(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.Priority.Tooltip"));
        
        GridBagConstraints gbc_prioritySpinner = new GridBagConstraints();
        gbc_prioritySpinner.insets = new Insets(0, 0, 5, 0);
        gbc_prioritySpinner.fill = GridBagConstraints.HORIZONTAL;
        gbc_prioritySpinner.gridx = 1;
        gbc_prioritySpinner.gridy = 4;
        add(prioritySpinner, gbc_prioritySpinner);
        
        JLabel lblAlertMsg = new JLabel(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.AlertMessage"));
        GridBagConstraints gbc_lblAlertMsg = new GridBagConstraints();
        gbc_lblAlertMsg.insets = new Insets(0, 0, 5, 5);
        gbc_lblAlertMsg.anchor = GridBagConstraints.ABOVE_BASELINE_TRAILING;
        gbc_lblAlertMsg.gridx = 0;
        gbc_lblAlertMsg.gridy = 5;
        add(lblAlertMsg, gbc_lblAlertMsg);
        
        textFieldAlertMsg = new JTextField(localizedBundle.getString("LocalizedSendAlertPanel.JTextField.AlertCustomMessage"));
        GridBagConstraints gbc_textFieldAlertMsg = new GridBagConstraints();
        gbc_textFieldAlertMsg.insets = new Insets(0, 0, 5, 0);
        gbc_textFieldAlertMsg.fill = GridBagConstraints.HORIZONTAL;
        gbc_textFieldAlertMsg.gridx = 1;
        gbc_textFieldAlertMsg.gridy = 5;
        add(textFieldAlertMsg, gbc_textFieldAlertMsg);
        textFieldAlertMsg.setColumns(10);
        
        JLabel lblAlertAdditionalInfo = new JLabel(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.AlertAdditionalInfo"));
        GridBagConstraints gbc_lblAlertAdditionalInfo = new GridBagConstraints();
        gbc_lblAlertAdditionalInfo.insets = new Insets(0, 0, 5, 5);
        gbc_lblAlertAdditionalInfo.anchor = GridBagConstraints.EAST;
        gbc_lblAlertAdditionalInfo.gridx = 0;
        gbc_lblAlertAdditionalInfo.gridy = 6;
        add(lblAlertAdditionalInfo, gbc_lblAlertAdditionalInfo);
        
        textFieldAlertAdditionalInfo = new JTextField(localizedBundle.getString("LocalizedSendAlertPanel.JTextField.AdditionalInfo"));
        GridBagConstraints gbc_textFieldAlertAdditionalInfo = new GridBagConstraints();
        gbc_textFieldAlertAdditionalInfo.insets = new Insets(0, 0, 5, 0);
        gbc_textFieldAlertAdditionalInfo.fill = GridBagConstraints.HORIZONTAL;
        gbc_textFieldAlertAdditionalInfo.gridx = 1;
        gbc_textFieldAlertAdditionalInfo.gridy = 6;
        add(textFieldAlertAdditionalInfo, gbc_textFieldAlertAdditionalInfo);
        textFieldAlertAdditionalInfo.setColumns(10);
        
        JLabel lblAlertDeclaration = new JLabel(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.LinkedDeclaration"));
        GridBagConstraints gbc_lblAlertDeclaration = new GridBagConstraints();
        gbc_lblAlertDeclaration.anchor = GridBagConstraints.EAST;
        gbc_lblAlertDeclaration.insets = new Insets(0, 0, 5, 5);
        gbc_lblAlertDeclaration.gridx = 0;
        gbc_lblAlertDeclaration.gridy = 7;
        add(lblAlertDeclaration, gbc_lblAlertDeclaration);
    
        comboBoxAlertDeclarations = new JComboBox<>();
        GridBagConstraints gbc_comboBoxAlertDeclaration = new GridBagConstraints();
        gbc_comboBoxAlertDeclaration.insets = new Insets(0, 0, 5, 0);
        gbc_comboBoxAlertDeclaration.fill = GridBagConstraints.HORIZONTAL;
        gbc_comboBoxAlertDeclaration.gridx = 1;
        gbc_comboBoxAlertDeclaration.gridy = 7;
        add(comboBoxAlertDeclarations, gbc_comboBoxAlertDeclaration);
        
        JLabel lblAlertIcon = new JLabel(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.AlertIcon"));
        GridBagConstraints gbc_lblAlertIcon = new GridBagConstraints();
        gbc_lblAlertIcon.insets = new Insets(0, 0, 5, 5);
        gbc_lblAlertIcon.anchor = GridBagConstraints.EAST;
        gbc_lblAlertIcon.gridx = 0;
        gbc_lblAlertIcon.gridy = 8;
        add(lblAlertIcon, gbc_lblAlertIcon);
    
        combBoxAlertIcons = new JComboBox<>();
        combBoxAlertIcons.setModel(new DefaultComboBoxModel<>(
                Arrays.stream(SeverityIcons.values())
                        .map(SeverityIconItem::new)
                        .toArray(SeverityIconItem[]::new)));
        comboBoxAlertDeclarations.addItem(new AlertDeclarationComboBoxOption(localizedBundle.getString("LocalizedSendAlertPanel.AlertDeclarationComboBoxOption.None"), null));
        GridBagConstraints gbc_combBoxAlertIcon = new GridBagConstraints();
        gbc_combBoxAlertIcon.insets = new Insets(0, 0, 5, 0);
        gbc_combBoxAlertIcon.fill = GridBagConstraints.HORIZONTAL;
        gbc_combBoxAlertIcon.gridx = 1;
        gbc_combBoxAlertIcon.gridy = 8;
        add(combBoxAlertIcons, gbc_combBoxAlertIcon);
        
        JLabel lblNotifications = new JLabel(localizedBundle.getString("LocalizedSendAlertPanel.JLabel.Notifications"));
        GridBagConstraints gbc_lblNotifications = new GridBagConstraints();
        gbc_lblNotifications.anchor = GridBagConstraints.EAST;
        gbc_lblNotifications.insets = new Insets(0, 0, 5, 5);
        gbc_lblNotifications.gridx = 0;
        gbc_lblNotifications.gridy = 9;
        add(lblNotifications, gbc_lblNotifications);
        
        JPanel panel = new JPanel();
        GridBagConstraints gbc_panel = new GridBagConstraints();
        gbc_panel.insets = new Insets(0, 0, 5, 0);
        gbc_panel.fill = GridBagConstraints.BOTH;
        gbc_panel.gridx = 1;
        gbc_panel.gridy = 9;
        add(panel, gbc_panel);
        panel.setLayout(new GridLayout(0, 2, 0, 0));
        
        chckbxPopup = new JCheckBox(localizedBundle.getString("LocalizedSendAlertPanel.JCheckBox.Popup"));
        chckbxPopup.setSelected(true);
        panel.add(chckbxPopup);
        
        chckbxSound = new JCheckBox(localizedBundle.getString("LocalizedSendAlertPanel.JCheckBox.Sound"));
        panel.add(chckbxSound);
    
        JButton btnSendCustomAlert = new JButton(localizedBundle.getString("LocalizedSendAlertPanel.JButton.SendCustomAlert"));
        btnSendCustomAlert.addActionListener(e -> {
            String mainText = textFieldAlertMsg.getText();
            String selectedDeclarationId = Optional
                .ofNullable((AlertDeclarationComboBoxOption) comboBoxAlertDeclarations.getSelectedItem())
                .map(option -> option.declarationId)
                .orElse(null);
            
            Layer1ApiSoundAlertMessage data = Layer1ApiSoundAlertMessage.builder()
                .setAlias(getAlias())
                .setTextInfo(mainText)
                .setAdditionalInfo(textFieldAlertAdditionalInfo.getText())
                .setSound(chckbxSound.isSelected() ? SoundSynthHelper.synthesize(mainText) : null)
                .setStatusListener((alertId, status) -> Log.info("onSoundAlertStatus: " + alertId + " " + status))
                .setSource(Layer1ApiLocalizedAlertDemo.class)
                .setShowPopup(chckbxPopup.isSelected())
                .setRepeatCount((Long) repeatsSpinner.getValue())
                .setRepeatDelay(Duration.ofMillis((Long) delaySpinner.getValue()))
                .setPriority((Integer) prioritySpinner.getValue())
                .setSeverityIcon(((SeverityIcons) combBoxAlertIcons.getSelectedItem()).icon)
                .setAlertDeclarationId(selectedDeclarationId)
                .build();
            
            callback.sendCustomAlert(data);
        });
        GridBagConstraints gbc_btnSendCustomAlert = new GridBagConstraints();
        gbc_btnSendCustomAlert.fill = GridBagConstraints.HORIZONTAL;
        gbc_btnSendCustomAlert.insets = new Insets(0, 0, 5, 0);
        gbc_btnSendCustomAlert.gridx = 1;
        gbc_btnSendCustomAlert.gridy = 10;
        add(btnSendCustomAlert, gbc_btnSendCustomAlert);
    
        comboBoxAlertDeclarations.addActionListener(e -> {
            Optional
                .ofNullable((AlertDeclarationComboBoxOption) comboBoxAlertDeclarations.getSelectedItem())
                .map(option -> option.declarationId)
                .map(settingsPerDeclarationId::get)
                .or(() -> Optional.of(Layer1ApiAlertSettingsMessage.defaultSettings(null, Layer1ApiLocalizedAlertDemo.class)))
                .ifPresent(this::updateSettingCheckboxes);
        });
    }
    
    private void updateSettingCheckboxes(Layer1ApiAlertSettingsMessage settingsMessage) {
        SwingUtilities.invokeLater(() -> {
            chckbxPopup.setSelected(settingsMessage.popup);
            chckbxSound.setSelected(settingsMessage.sound);
        });
    }

    public void addAlias(String alias) {
        SwingUtilities.invokeLater(() -> comboBoxAliases.addItem(alias));
    }

    public void removeAlias(String alias) {
        SwingUtilities.invokeLater(() -> comboBoxAliases.removeItem(alias));
    }
    
    private String getAlias() {
        String source = (String) comboBoxAliases.getSelectedItem();
        if (source.equals(sourceGlobal)) {
            source = null;
        }
        return source;
    }

    public void addAlertDeclaration(Layer1ApiSoundAlertDeclarationMessage declaration) {
        SwingUtilities.invokeLater(() -> comboBoxAlertDeclarations.addItem(new AlertDeclarationComboBoxOption(declaration)));
    }
    
    public void removeAlertDeclaration(Layer1ApiSoundAlertDeclarationMessage declaration) {
        SwingUtilities.invokeLater(() -> {
            settingsPerDeclarationId.remove(declaration.id);
            comboBoxAlertDeclarations.removeItem(new AlertDeclarationComboBoxOption(declaration));
        });
    }
    
    public void updateAlertSettings(Layer1ApiAlertSettingsMessage settings) {
        settingsPerDeclarationId.put(settings.declarationId, settings);
        Optional
            .ofNullable((AlertDeclarationComboBoxOption) comboBoxAlertDeclarations.getSelectedItem())
            .map(selectedOption -> selectedOption.declarationId)
            .filter(declarationId -> declarationId.equals(settings.declarationId))
            .ifPresent(declarationId -> this.updateSettingCheckboxes(settings));
    }
 
    
    private static class AlertDeclarationComboBoxOption {
        final String triggerDescription;
        final String declarationId;
    
        public AlertDeclarationComboBoxOption(Layer1ApiSoundAlertDeclarationMessage message) {
            this(message.triggerDescription, message.id);
        }
    
        public AlertDeclarationComboBoxOption(String triggerDescription, String declarationId) {
            this.triggerDescription = triggerDescription;
            this.declarationId = declarationId;
        }
    
        @Override
        public String toString() {
            return triggerDescription;
        }
    
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AlertDeclarationComboBoxOption)) {
                return false;
            }
            AlertDeclarationComboBoxOption that = (AlertDeclarationComboBoxOption) o;
            return Objects.equals(declarationId, that.declarationId);
        }
    
        @Override
        public int hashCode() {
            return Objects.hash(declarationId);
        }
    }
}
