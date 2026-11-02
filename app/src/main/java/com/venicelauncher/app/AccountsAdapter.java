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

        holder.tvName.setText(profile.name);
        holder.tvStatus.setText(isActive ? "Active Account" : "Tap to switch");
        holder.tvStatus.setTextColor(holder.itemView.getContext().getColor(
                isActive ? R.color.status_active : R.color.text_secondary
        ));

        holder.ivActive.setVisibility(isActive ? View.VISIBLE : View.GONE);

        // Hide delete if active or if it's the only account
        if (accounts.size() <= 1 || isActive) {
            holder.btnDelete.setVisibility(View.GONE);
        } else {
            holder.btnDelete.setVisibility(View.VISIBLE);
            holder.btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onAccountDelete(profile);
                    }
                }
            });
        }

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onAccountSelected(profile);
                }
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
