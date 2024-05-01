package velox.api.layer1.simpledemo.multipleaccount;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import velox.api.layer1.Layer1ApiAdminAdapter;
import velox.api.layer1.Layer1ApiFinishable;
import velox.api.layer1.Layer1ApiInstrumentListener;
import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.Layer1ApiTradingListener;
import velox.api.layer1.common.ListenableHelper;
import velox.api.layer1.common.Log;
import velox.api.layer1.common.helper.AccountListManager;
import velox.api.layer1.data.BalanceInfo;
import velox.api.layer1.data.ExecutionInfo;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.OrderInfoUpdate;
import velox.api.layer1.data.StatusInfo;
import velox.api.layer1.layers.strategies.interfaces.CalculatedResultListener;
import velox.api.layer1.layers.strategies.interfaces.CustomGeneratedEvent;
import velox.api.layer1.layers.strategies.interfaces.CustomGeneratedEventAliased;
import velox.api.layer1.layers.strategies.interfaces.InvalidateInterface;
import velox.api.layer1.layers.strategies.interfaces.OnlineCalculatable;
import velox.api.layer1.layers.strategies.interfaces.OnlineValueCalculatorAdapter;
import velox.api.layer1.messages.GeneratedEventInfo;
import velox.api.layer1.messages.Layer1ApiUserMessageAddStrategyUpdateGenerator;
import velox.api.layer1.messages.TradingAccountsInfoMessage;
import velox.api.layer1.messages.UserMessageLayersChainCreatedTargeted;
import velox.api.layer1.messages.indicators.IndicatorColorScheme;
import velox.api.layer1.messages.indicators.IndicatorLineStyle;

import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator;
import velox.api.layer1.messages.indicators.StrategyUpdateGenerator;


/**
 * Base class for strategies to demonstrate multiple account support.
 * See {@link NoMultipleAccountSupportDemo} for a strategy without multiple account support.
 * See {@link MultipleAccountSupportDemo} for a strategy with multiple account support.
 */
public class MultipleAccountSupportDemoBase implements
        Layer1ApiTradingListener,
        Layer1ApiFinishable,
        Layer1ApiAdminAdapter,
        Layer1ApiInstrumentListener,
        OnlineCalculatable {
    
    private final AccountListManager accountListManager = new AccountListManager();
    
    private static class SampleEvent implements CustomGeneratedEvent {

        @Override
        public long getTime() {
            return 0;
        }
        
        @Override
        public Object clone() {
            return null;
        }
    }
    
    private final String providerName = this.getClass().getName() + "Sample";
    
    private final String treeName = providerName + "_Tree";
    private final String indicatorName = providerName + "_Indicator";
    
    private final Layer1ApiProvider provider;
    
    private final Map<String, String> indicatorsFullNameToUserName = new HashMap<>();
    
    public MultipleAccountSupportDemoBase(Layer1ApiProvider provider) {
        this.provider = provider;
        
        ListenableHelper.addListeners(provider, this);
    }
    
    @Override
    public void finish() {
        synchronized (indicatorsFullNameToUserName) {
            for (String userName : indicatorsFullNameToUserName.values()) {
                provider.sendUserMessage(new Layer1ApiUserMessageModifyIndicator(MultipleAccountSupportDemoBase.class, userName, false));
            }
        }
        
        provider.sendUserMessage(getGeneratorMessage(false));
    }
    
    private Layer1ApiUserMessageModifyIndicator getIndicatorUserMessageAdd() {
        return Layer1ApiUserMessageModifyIndicator.builder(MultipleAccountSupportDemoBase.class, indicatorName)
                .setIsAdd(true)
                .setGraphType(Layer1ApiUserMessageModifyIndicator.GraphType.BOTTOM)
                .setOnlineCalculatable(this)
                .setIndicatorColorScheme(new IndicatorColorScheme() {
                    @Override
                    public ColorDescription[] getColors() {
                        return new ColorDescription[] {
                                new ColorDescription(MultipleAccountSupportDemoBase.class, "Some color", Color.RED, false),
                        };
                    }
                    
                    @Override
                    public String getColorFor(Double value) {
                        return "Some color";
                    }
                    
                    @Override
                    public ColorIntervalResponse getColorIntervalsList(double valueFrom, double valueTo) {
                        return new ColorIntervalResponse(new String[] {"Some color"}, new double[] {});
                    }
                })
                .setIndicatorLineStyle(IndicatorLineStyle.NONE)
                .build();
    }
    
    @Override
    public void onUserMessage(Object data) {
        boolean accountListChanged = accountListManager.onUserMessage(data);
        
        if (accountListChanged) {
            Log.info("Account list changed (accounts count = " + accountListManager.getAccounts().size() + ")");
        }
        
        if (data instanceof TradingAccountsInfoMessage message) {
            Log.info("TradingAccountsInfoMessage: " + message);
        }
        
        
        if (data.getClass() == UserMessageLayersChainCreatedTargeted.class) {
            UserMessageLayersChainCreatedTargeted message = (UserMessageLayersChainCreatedTargeted) data;
            if (message.targetClass == getClass()) {
                
                // Get DataStructureInterface for the indicator here if needed ...
                
                Layer1ApiUserMessageModifyIndicator indicatorUserMessageAdd = getIndicatorUserMessageAdd();
                indicatorsFullNameToUserName.put(indicatorUserMessageAdd.fullName, indicatorUserMessageAdd.userName);
                provider.sendUserMessage(indicatorUserMessageAdd);
                
                provider.sendUserMessage(getGeneratorMessage(true));
            }
        }
        
    }
    
    @Override
    public void onOrderUpdated(OrderInfoUpdate orderInfoUpdate) {
        accountListManager.onOrderUpdated(orderInfoUpdate);
        
        // Multi-account data should be available here if Strategy is annotated with @Layer1MultiAccountTradingSupported.
        Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrNull(orderInfoUpdate.accountId);
        Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] MultipleAccountSupportDemoBase#onOrderUpdated: " + orderInfoUpdate);
    }
    
    @Override
    public void onOrderExecuted(ExecutionInfo executionInfo) {
        // Multi-account data should be available here if Strategy is annotated with @Layer1MultiAccountTradingSupported.
        Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrderOrNull(executionInfo.orderId);
        Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] MultipleAccountSupportDemoBase#onOrderExecuted: " + executionInfo);
    }
    
    @Override
    public void onStatus(StatusInfo statusInfo) {
        Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrNull(statusInfo.accountId);
        Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] MultipleAccountSupportDemoBase#onStatus: " + statusInfo);
    }
    
    @Override
    public void onBalance(BalanceInfo balanceInfo) {
        Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrNull(balanceInfo.accountId);
        Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] MultipleAccountSupportDemoBase#onBalance: " + balanceInfo);
    }
    
    @Override
    public void onInstrumentAdded(String alias, InstrumentInfo instrumentInfo) {
    }
    
    @Override
    public void calculateValuesInRange(String indicatorName, String indicatorAlias, long t0, long intervalWidth, int intervalsNumber,
                                       CalculatedResultListener listener) {
       
        listener.setCompleted();
    }
    
    @Override
    public OnlineValueCalculatorAdapter createOnlineValueCalculator(String indicatorName, String indicatorAlias, long time,
                                                                    Consumer<Object> listener, InvalidateInterface invalidateInterface) {
        return new OnlineValueCalculatorAdapter() {
            
            @Override
            public void onStatus(StatusInfo statusInfo) {
                // Multi-account data should be available here if Strategy is annotated with @Layer1MultiAccountTradingSupported.
                Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrNull(statusInfo.accountId);
                Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] OnlineValueCalculatorAdapter#onStatus: " + statusInfo);
            }
            
            @Override
            public void onOrderUpdated(OrderInfoUpdate orderInfoUpdate) {
                // Multi-account data should be available here if Strategy is annotated with @Layer1MultiAccountTradingSupported.
                Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrNull(orderInfoUpdate.accountId);
                Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] OnlineValueCalculatorAdapter#onOrderUpdated: " + orderInfoUpdate);
            }
            
            @Override
            public void onOrderExecuted(ExecutionInfo executionInfo) {
                // Multi-account data should be available here if Strategy is annotated with @Layer1MultiAccountTradingSupported.
                Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrderOrNull(executionInfo.orderId);
                Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] OnlineValueCalculatorAdapter#onOrderExecuted: " + executionInfo);
            }
            
            @Override
            public void onBalance(BalanceInfo balanceInfo) {
                // Multi-account data should be available here if Strategy is annotated with @Layer1MultiAccountTradingSupported.
                Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrNull(balanceInfo.accountId);
                Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] OnlineValueCalculatorAdapter#onStatus: " + balanceInfo);;
            }
            
            @Override
            public void onUserMessage(Object data) {
                if (data instanceof TradingAccountsInfoMessage message) {
                    // TradingAccountsListMessage should be received here.
                    Log.info("OnlineValueCalculatorAdapter#onUserMessage: " + message);
                }
            }
            
        };
    }
    
    private Layer1ApiUserMessageAddStrategyUpdateGenerator getGeneratorMessage(boolean isAdd) {
        return new Layer1ApiUserMessageAddStrategyUpdateGenerator(MultipleAccountSupportDemoBase.class, treeName, isAdd, true, new StrategyUpdateGenerator() {
            private Consumer<CustomGeneratedEventAliased> consumer;
            
            @Override
            public void setGeneratedEventsConsumer(Consumer<CustomGeneratedEventAliased> consumer) {
                this.consumer = consumer;
            }
            
            @Override
            public Consumer<CustomGeneratedEventAliased> getGeneratedEventsConsumer() {
                return consumer;
            }
            
            @Override
            public void onStatus(StatusInfo statusInfo) {
                // Multi-account data should be available here if Strategy is annotated with @Layer1MultiAccountTradingSupported.
                Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrNull(statusInfo.accountId);
                Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] Layer1ApiUserMessageAddStrategyUpdateGenerator#onStatus: " + statusInfo);
            }
            
            @Override
            public void onOrderUpdated(OrderInfoUpdate orderInfoUpdate) {
                // Multi-account data should be available here if Strategy is annotated with @Layer1MultiAccountTradingSupported.
                Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrNull(orderInfoUpdate.accountId);
                Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] Layer1ApiUserMessageAddStrategyUpdateGenerator#onOrderUpdated: " + orderInfoUpdate);
            }
            
            @Override
            public void onOrderExecuted(ExecutionInfo executionInfo) {
                // Multi-account data should be available here if Strategy is annotated with @Layer1MultiAccountTradingSupported.
                Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrderOrNull(executionInfo.orderId);
                Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] Layer1ApiUserMessageAddStrategyUpdateGenerator#onOrderExecuted: " + executionInfo);
            }
            
            @Override
            public void onBalance(BalanceInfo balanceInfo) {
                // Multi-account data should be available here if Strategy is annotated with @Layer1MultiAccountTradingSupported.
                Boolean isPrimaryAccount = accountListManager.isPrimaryAccountOrNull(balanceInfo.accountId);
                Log.info("[isPrimaryAccount=" + isPrimaryAccount + "] Layer1ApiUserMessageAddStrategyUpdateGenerator#onBalance: " + balanceInfo);
            }
            
            @Override
            public void onUserMessage(Object data) {
                if (data instanceof TradingAccountsInfoMessage message) {
                    // TradingAccountsListMessage should be received here.
                    Log.info("Layer1ApiUserMessageAddStrategyUpdateGenerator#onUserMessage: " + message);
                }
            }
            
            @Override
            public void setTime(long time) {
            }
        }, new GeneratedEventInfo[] {new GeneratedEventInfo(SampleEvent.class)});
    }
    
    @Override
    public void onInstrumentRemoved(String alias) {
    }
    
    @Override
    public void onInstrumentNotFound(String symbol, String exchange, String type) {
    }
    
    @Override
    public void onInstrumentAlreadySubscribed(String symbol, String exchange, String type) {
    }
}
