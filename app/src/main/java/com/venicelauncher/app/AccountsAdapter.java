package com.venicelauncher.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AccountsAdapter extends RecyclerView.Adapter<AccountsAdapter.ViewHolder> {

    public interface OnAccountClickListener {
        void onAccountSelected(AccountManager.AccountProfile profile);
        void onAccountDelete(AccountManager.AccountProfile profile);
    }

    private final List<AccountManager.AccountProfile> accounts;
    private final String activeAccountId;
    private final OnAccountClickListener listener;

    public AccountsAdapter(List<AccountManager.AccountProfile> accounts, String activeAccountId, OnAccountClickListener listener) {
        this.accounts = accounts;
        this.activeAccountId = activeAccountId;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_account, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final AccountManager.AccountProfile profile = accounts.get(position);
        boolean isActive = profile.id.equals(activeAccountId);

        if (profile.isIncognito) {
            holder.tvName.setText("🕶  " + profile.name);
            if (isActive) {
                holder.tvStatus.setText("Active Incognito · Erased on switch/exit");
                holder.tvStatus.setTextColor(holder.itemView.getContext().getColor(R.color.venice_stucco_mid));
            } else {
                holder.tvStatus.setText("Temporary Session · Erased on switch");
                holder.tvStatus.setTextColor(holder.itemView.getContext().getColor(R.color.venice_muted));
            }
            holder.ivActive.setImageResource(R.drawable.ic_incognito);
            holder.ivActive.setColorFilter(holder.itemView.getContext().getColor(R.color.venice_stucco_mid));
            holder.ivActive.setVisibility(View.VISIBLE);
        } else {
            holder.tvName.setText(profile.name);
            holder.tvStatus.setText(isActive ? "Active Account" : "Tap to switch");
            holder.tvStatus.setTextColor(holder.itemView.getContext().getColor(
                    isActive ? R.color.venice_signal : R.color.venice_muted
            ));
            holder.ivActive.setImageResource(R.drawable.ic_check);
            holder.ivActive.setColorFilter(holder.itemView.getContext().getColor(R.color.venice_signal));
            holder.ivActive.setVisibility(isActive ? View.VISIBLE : View.GONE);
        }

        // Allow deleting incognito accounts or any non-active permanent accounts
        if (profile.isIncognito) {
            holder.btnDelete.setVisibility(View.VISIBLE);
            holder.btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onAccountDelete(profile);
                }
            });
        } else if (accounts.size() <= 1 || isActive) {
            holder.btnDelete.setVisibility(View.GONE);
        } else {
            holder.btnDelete.setVisibility(View.VISIBLE);
            holder.btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onAccountDelete(profile);
                }
            });
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAccountSelected(profile);
            }
        });
    }

    @Override
    public int getItemCount() {
        return accounts.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public ImageView ivActive;
        public TextView tvName;
        public TextView tvStatus;
        public ImageButton btnDelete;

        public ViewHolder(View itemView) {
            super(itemView);
            ivActive = itemView.findViewById(R.id.iv_account_active);
            tvName = itemView.findViewById(R.id.tv_account_name);
            tvStatus = itemView.findViewById(R.id.tv_account_status);
            btnDelete = itemView.findViewById(R.id.btn_delete_account);
        }
    }
}
