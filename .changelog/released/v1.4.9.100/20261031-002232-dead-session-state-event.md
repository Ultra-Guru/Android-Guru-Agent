<!-- pr: dead-session-state-event -->

- 两处只定义不消费的 SessionStateChanged 死事件移除：TerminalControlEvent（TerminalControllerImpl 从未发射）与 TerminalApiEvent（TerminalApi 零实例化）—— 契约测试同步从 4 型收窄为 3 型，防止后来人误以为事件面可用
