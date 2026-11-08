package h.Hchat.ui.miuix;

import android.view.View;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import androidx.navigationevent.NavigationEventDispatcherOwner;
import androidx.navigationevent.ViewTreeNavigationEventDispatcherOwner;
import androidx.savedstate.SavedStateRegistryOwner;
import androidx.savedstate.ViewTreeSavedStateRegistryOwner;

public final class EmbeddedComposeOwnerInstaller {
    private EmbeddedComposeOwnerInstaller() {
    }

    public static void install(
            View view,
            LifecycleOwner lifecycleOwner,
            SavedStateRegistryOwner savedStateRegistryOwner,
            ViewModelStoreOwner viewModelStoreOwner,
            NavigationEventDispatcherOwner navigationEventDispatcherOwner
    ) {
        ViewTreeLifecycleOwner.set(view, lifecycleOwner);
        ViewTreeSavedStateRegistryOwner.set(view, savedStateRegistryOwner);
        ViewTreeViewModelStoreOwner.set(view, viewModelStoreOwner);
        ViewTreeNavigationEventDispatcherOwner.set(view, navigationEventDispatcherOwner);
    }

    public static void clear(View view) {
        ViewTreeLifecycleOwner.set(view, null);
        ViewTreeSavedStateRegistryOwner.set(view, null);
        ViewTreeViewModelStoreOwner.set(view, null);
        ViewTreeNavigationEventDispatcherOwner.set(view, null);
    }

    public static boolean hasLifecycleOwner(View view) {
        return ViewTreeLifecycleOwner.get(view) != null;
    }
}
