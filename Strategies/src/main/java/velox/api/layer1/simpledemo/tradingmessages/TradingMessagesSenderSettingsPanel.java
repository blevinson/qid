package velox.api.layer1.simpledemo.tradingmessages;

import velox.api.layer1.messages.Layer1ApiChangeTradingModeMessage;
import velox.api.layer1.messages.Layer1ApiSetOrderSizeMessage;
import velox.api.layer1.messages.Layer1ApiTradingMessageWithCallback;
import velox.gui.StrategyPanel;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.InputVerifier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class TradingMessagesSenderSettingsPanel extends StrategyPanel {
    public static TradingMessagesSenderSettingsPanel newPanel(final String name, final Consumer<Object> messageConsumer, final AtomicBoolean isWorking) {
        return new TradingMessagesSenderSettingsPanel(name, messageConsumer, isWorking);
    }
    
    private enum TradingAction {
        ENABLE,
        DISABLE
    }
    
    private static final String TRADING_ENABLE_TITLE = "Send Trading Enable Message";
    private static final String SET_ORDER_SIZE_TITLE = "Send Set Order Size Message";
    private final boolean initialized;
    private final JComboBox<String> comboBoxAliasesTradingEnable;
    private final JComboBox<String> comboBoxAliasesSetOrderSize;
    private final Consumer<Object> messageConsumer;
    private final JComponent workingPanel;
    private final JComponent disabledPanel;
    private final AtomicBoolean isWorking;

    private TradingMessagesSenderSettingsPanel(final String name, final Consumer<Object> messageConsumer, final AtomicBoolean isWorking) {
        super(name);
        setName(name);
        setBorder(BorderFactory.createEmptyBorder());
        this.isWorking = isWorking;
        initialized = true;
        comboBoxAliasesTradingEnable = newComboBox();
        comboBoxAliasesSetOrderSize = newComboBox();
        this.messageConsumer = messageConsumer;
        workingPanel = getMainPanel();
        disabledPanel = getDisabledPanel();
        this.add(disabledPanel);
        this.add(workingPanel);
    }

    @Override
    public void setName(final String name) {
        if (!initialized) {
            super.setName(name);
        }
    }

    @Override
    public void setBorder(final Border border) {
        if (!initialized) {
            super.setBorder(border);
        }
    }

    public void updateInstruments(List<String> aliases) {
        updateComboBox(comboBoxAliasesTradingEnable, aliases);
        updateComboBox(comboBoxAliasesSetOrderSize, aliases);
    }

    public void updateIfNeeded() {
        workingPanel.setVisible(isWorking.get());
        disabledPanel.setVisible(!isWorking.get());
    }

    private JPanel getMainPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel tradingEnableBlock = new JPanel();
        tradingEnableBlock.setLayout(new BoxLayout(tradingEnableBlock, BoxLayout.Y_AXIS));
        tradingEnableBlock.setBorder(BorderFactory.createTitledBorder(TRADING_ENABLE_TITLE));

        JLabel comboBoxTradingEnableAliasesLabel = new JLabel("Select instrument:");

        JLabel actionComboBoxLabel = new JLabel("Select action:");
        JComboBox<TradingAction> actionComboBox = new JComboBox<>(TradingAction.values());

        JLabel modeComboBoxLabel = new JLabel("Select Trading mode:");
        JComboBox<Layer1ApiChangeTradingModeMessage.Mode> modeComboBox = new JComboBox<>(Layer1ApiChangeTradingModeMessage.Mode.values());

        JButton tradingEnableButton = new JButton("Send TradingEnable Message");
        tradingEnableButton.addActionListener(e -> {
            String alias = (String) comboBoxAliasesTradingEnable.getSelectedItem();
            if (alias == null || alias.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Alias cannot be empty!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            Layer1ApiChangeTradingModeMessage.Mode tradingMode;
            try {
                tradingMode = (Layer1ApiChangeTradingModeMessage.Mode) modeComboBox.getSelectedItem();
            } catch (ClassCastException exception) {
                JOptionPane.showMessageDialog(null, "Trading Mode cannot be empty!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            boolean enable;
            try {
                TradingAction action = (TradingAction) actionComboBox.getSelectedItem();
                enable = action == TradingAction.ENABLE;
            } catch (ClassCastException exception) {
                JOptionPane.showMessageDialog(null, "Action cannot be empty!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            String messageText = "Layer1ApiChangeTradingModeMessage with alias: " + alias + ", enable: " + enable + ", tradingMode: " + tradingMode;
            messageConsumer.accept(new Layer1ApiChangeTradingModeMessage(alias, enable, tradingMode, getResponseListener(messageText)));
        });

        tradingEnableBlock.add(comboBoxTradingEnableAliasesLabel);
        tradingEnableBlock.add(comboBoxAliasesTradingEnable);
        tradingEnableBlock.add(actionComboBoxLabel);
        tradingEnableBlock.add(actionComboBox);
        tradingEnableBlock.add(modeComboBoxLabel);
        tradingEnableBlock.add(modeComboBox);
        tradingEnableBlock.add(tradingEnableButton);

        JPanel setOrderSizeBlock = new JPanel();
        setOrderSizeBlock.setLayout(new BoxLayout(setOrderSizeBlock, BoxLayout.Y_AXIS));
        setOrderSizeBlock.setBorder(BorderFactory.createTitledBorder(SET_ORDER_SIZE_TITLE));
        JLabel comboBoxSetOrderSizeAliasesLabel = new JLabel("Select instrument:");

        JLabel integerFieldLabel = new JLabel("Select new order size:");
        JLabel integerFieldLabelNote = new JLabel("(Note that the size multilier will be applied)");
        integerFieldLabelNote.setFont(new Font("", Font.ITALIC, 12));
        JTextField integerField = new JTextField(10);
        integerField.setInputVerifier(new InputVerifier() {
            @Override
            public boolean verify(JComponent input) {
                try {
                    int value = Integer.parseInt(((JTextField) input).getText());
                    if (value >= Layer1ApiSetOrderSizeMessage.ORDER_SIZE_MIN_VALUE && value <= Layer1ApiSetOrderSizeMessage.ORDER_SIZE_MAX_VALUE) {
                        return true;
                    }
                    JOptionPane.showMessageDialog(null, String.format("The orderSize value should be between %s and %s",
                            Layer1ApiSetOrderSizeMessage.ORDER_SIZE_MIN_VALUE, Layer1ApiSetOrderSizeMessage.ORDER_SIZE_MAX_VALUE));
                    return false;
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(null, "Please enter an integer!");
                    return false;
                }
            }
        });

        JButton sendSetOrderSizeMessage = new JButton("Send SetOrderSize Message");
        sendSetOrderSizeMessage.addActionListener(e -> {
            String alias = (String) comboBoxAliasesSetOrderSize.getSelectedItem();
            if (alias == null || alias.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Alias cannot be empty!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            int orderSize;
            try {
                orderSize = Integer.parseInt(integerField.getText());
            } catch (NumberFormatException | NullPointerException exception) {
                JOptionPane.showMessageDialog(null, "Order size cannot be empty!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            String messageText = "Layer1ApiSetOrderSizeMessage with alias: " + alias + ", orderSize: " + orderSize;
            messageConsumer.accept(new Layer1ApiSetOrderSizeMessage(alias, orderSize, getResponseListener(messageText)));
        });

        setOrderSizeBlock.add(comboBoxSetOrderSizeAliasesLabel);
        setOrderSizeBlock.add(comboBoxAliasesSetOrderSize);
        setOrderSizeBlock.add(integerFieldLabel);
        setOrderSizeBlock.add(integerFieldLabelNote);
        setOrderSizeBlock.add(integerField);
        setOrderSizeBlock.add(sendSetOrderSizeMessage);

        panel.add(tradingEnableBlock);
        panel.add(setOrderSizeBlock);
        panel.setVisible(isWorking.get());
        return panel;
    }

    public static <E> JComboBox<E> newComboBox() {
        final JComboBox<E> comboBox = new JComboBox<>();
        final Runnable update = () -> {
            @SuppressWarnings("unchecked") final E item = (E) comboBox.getSelectedItem();
            comboBox.setPrototypeDisplayValue(item);
        };
        comboBox.addItemListener(e -> update.run());
        return comboBox;
    }

    private <T> void updateComboBox(final JComboBox<T> comboBox, final List<T> items) {
        SwingUtilities.invokeLater(() -> {
            @SuppressWarnings("unchecked") final T item = (T) comboBox.getModel().getSelectedItem();
            final ComboBoxModel<T> model = new DefaultComboBoxModel<>(new Vector<>(items));
            comboBox.setModel(model);
            model.setSelectedItem(item);
        });
    }

    private JComponent getDisabledPanel() {
        StrategyPanel panel = new StrategyPanel("Strategy is disabled");
        panel.setLayout(new FlowLayout(FlowLayout.LEFT));
        JLabel label = new JLabel(String.format("<html>Enable the '%s' checkbox to activate<br>this add-on.</html>", TradingMessagesSender.NAME));
        panel.setVisible(!this.isWorking.get());
        panel.add(label);
        return panel;
    }

    private Layer1ApiTradingMessageWithCallback.TradingMessageResponseListener getResponseListener(String message) {
        return response -> showMessageDialog(String.format("Recieved response by message %s, the response was %s", message, response));
    }

    private void showMessageDialog(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null, message));
    }
}
