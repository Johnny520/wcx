# WeChat 8.0.79 DEX target facts verified from supplied APK

APK package/version metadata: `com.tencent.mm`, versionName `8.0.79`, versionCode `3200`.
The following facts were read from the DEX class definitions and method-id/class-data tables in the supplied APK; they are not runtime Hook tests.

| Target | DEX | Direct superclass | Verified declared members |
|---|---|---|---|
| `com.tencent.mm.ui.LauncherUI` | `classes13.dex` | `com.tencent.mm.plugin.secdata.ui.MMSecDataFragmentActivity` | `getInstance(): LauncherUI`, `onCreate(Bundle): void`, `onResume(): void` |
| `com.tencent.mm.ui.chatting.ChattingUI` | `classes11.dex` | `com.tencent.mm.plugin.secdata.ui.MMSecDataFragmentActivity` | `onCreate(Bundle): void`, `onResume(): void` |
| `com.tencent.mm.ui.conversation.ConversationListView` | `classes17.dex` | `android.widget.ListView` | `getRealCount(): int`, `getHeaderViewList(): ArrayList`, among other methods |
| `com.tencent.mm.plugin.sns.ui.SnsTimeLineUI` | `classes3.dex` | `com.tencent.mm.hellhoundlib.activities.HellActivity` | Only a constructor is declared on this class in the supplied DEX; lifecycle methods are inherited |
| `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI` | `classes3.dex` | `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsJankUI` | `onCreate(Bundle): void`, `onResume(): void`, and other lifecycle methods |
| `com.tencent.mm.plugin.webview.ui.tools.WebViewUI` | `classes8.dex` | `com.tencent.mm.plugin.secdata.ui.MMSecDataActivity` | `onCreate(Bundle): void`, `onResume(): void`, `onStart(): void` |
| `com.tencent.mm.pluginsdk.ui.chat.ChatFooter` | `classes3.dex` | `android.widget.FrameLayout` | `getLastText(): String`, plus `getLastContent(): String` and other methods |

The dynamic class-feature registry was corrected for the verified class names, direct superclasses, and member signatures above. `AbsListView` is a class rather than an interface, so it was removed from the old `ConversationList` and `ContactList` interface constraints. `ContactList` still needs a precise target class before it can be considered fully mapped.

These verified names help anchor the 8.0.79 profile, but do not establish that every WCX feature delegate is mapped to the right class/method. Full compatibility requires mapping each real feature and validating its actual Hook call sites.
