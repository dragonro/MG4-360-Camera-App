package com.drivehub.kamera;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.ViewGroup;
import android.view.View;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.TextView;

final class OtaDialogs {

    private OtaDialogs() {
    }

    static Dialog showRefreshDialog(
            Context context,
            OtaUpdateManager.UpdateInfo info,
            Runnable onRefresh
    ) {
        Dialog dialog = createBaseDialog(context, R.layout.dialog_ota_refresh);
        TextView message = dialog.findViewById(R.id.tvOtaRefreshMessage);
        View refreshButton = dialog.findViewById(R.id.btnOtaRefresh);
        View closeButton = dialog.findViewById(R.id.btnOtaClose);

        if (message != null) {
            message.setText(resolveRefreshMessage(context, info));
        }
        if (refreshButton != null) {
            refreshButton.setOnClickListener(v -> {
                dialog.dismiss();
                if (onRefresh != null) onRefresh.run();
            });
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }

        showCentered(dialog, 540, 0);
        return dialog;
    }

    static Dialog showConfirmDialog(
            Context context,
            String message,
            String confirmText,
            Runnable onConfirm
    ) {
        // Reuse the refresh dialog shell because the confirmation UI has the same structure:
        // one body message plus a dismiss and primary action button.
        Dialog dialog = createBaseDialog(context, R.layout.dialog_ota_refresh);
        TextView messageView = dialog.findViewById(R.id.tvOtaRefreshMessage);
        TextView refreshButton = dialog.findViewById(R.id.btnOtaRefresh);
        View closeButton = dialog.findViewById(R.id.btnOtaClose);

        if (messageView != null) {
            messageView.setText(message);
        }
        if (refreshButton != null) {
            refreshButton.setText(confirmText);
            refreshButton.setOnClickListener(v -> {
                dialog.dismiss();
                if (onConfirm != null) onConfirm.run();
            });
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }

        showCentered(dialog, 540, 0);
        return dialog;
    }

    static ProgressDialogHandle showProgressDialog(
            Context context,
            String titleText,
            Runnable onOpenDownloads,
            Runnable onRetryDownload,
            Runnable onInstallUpdate
    ) {
        Dialog dialog = createBaseDialog(context, R.layout.dialog_ota_download_progress);
        TextView title = dialog.findViewById(R.id.tvOtaProgressTitle);
        TextView status = dialog.findViewById(R.id.tvOtaProgressStatus);
        ProgressBar progress = dialog.findViewById(R.id.pbOtaDownload);
        View openDownloadsButton = dialog.findViewById(R.id.btnOtaOpenDownloads);
        View retryDownloadButton = dialog.findViewById(R.id.btnOtaRetryDownload);
        View installUpdateButton = dialog.findViewById(R.id.btnOtaInstallUpdate);
        View closeButton = dialog.findViewById(R.id.btnOtaClose);

        if (title != null) {
            title.setText(titleText);
        }
        if (openDownloadsButton != null) {
            openDownloadsButton.setOnClickListener(v -> {
                if (onOpenDownloads != null) onOpenDownloads.run();
            });
        }
        if (retryDownloadButton != null) {
            retryDownloadButton.setOnClickListener(v -> {
                if (onRetryDownload != null) onRetryDownload.run();
            });
        }
        if (installUpdateButton != null) {
            installUpdateButton.setOnClickListener(v -> {
                if (onInstallUpdate != null) onInstallUpdate.run();
            });
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }

        showCentered(dialog, 560, 0);
        return new ProgressDialogHandle(dialog, status, progress, retryDownloadButton, installUpdateButton);
    }

    static Dialog showMessageDialog(Context context, String message) {
        Dialog dialog = createBaseDialog(context, R.layout.dialog_ota_refresh);
        TextView messageView = dialog.findViewById(R.id.tvOtaRefreshMessage);
        View refreshButton = dialog.findViewById(R.id.btnOtaRefresh);
        View closeButton = dialog.findViewById(R.id.btnOtaClose);

        if (messageView != null) {
            messageView.setText(message);
        }
        if (refreshButton != null) {
            refreshButton.setVisibility(View.GONE);
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dialog.dismiss());
        }

        showCentered(dialog, 540, 0);
        return dialog;
    }

    private static Dialog createBaseDialog(Context context, int layoutResId) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutResId);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }

    private static void showCentered(Dialog dialog, int widthDp, int heightDp) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        float density = dialog.getContext().getResources().getDisplayMetrics().density;
        int widthPx = (int) (widthDp * density);
        int heightPx = heightDp > 0 ? (int) (heightDp * density) : ViewGroup.LayoutParams.WRAP_CONTENT;
        window.setLayout(widthPx, heightPx);
    }

    private static CharSequence resolveRefreshMessage(Context context, OtaUpdateManager.UpdateInfo info) {
        if (info != null && info.success && info.updateAvailable) {
            return context.getString(R.string.ota_dialog_update_available_message, info.latestVersion);
        }
        if (info != null && info.success) {
            return context.getString(R.string.ota_dialog_up_to_date_message);
        }
        return context.getString(R.string.ota_dialog_check_failed_message);
    }

    static final class ProgressDialogHandle {
        final Dialog dialog;
        final TextView statusView;
        final ProgressBar progressBar;
        final View retryButton;
        final View installButton;

        ProgressDialogHandle(Dialog dialog, TextView statusView, ProgressBar progressBar, View retryButton, View installButton) {
            this.dialog = dialog;
            this.statusView = statusView;
            this.progressBar = progressBar;
            this.retryButton = retryButton;
            this.installButton = installButton;
        }
    }
}
