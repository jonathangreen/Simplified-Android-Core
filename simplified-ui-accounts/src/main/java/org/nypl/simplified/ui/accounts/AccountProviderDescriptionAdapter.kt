package org.nypl.simplified.ui.accounts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.nypl.simplified.accounts.api.AccountProviderDescription
import org.nypl.simplified.ui.images.ImageAccountIcons
import org.nypl.simplified.ui.images.ImageLoaderType

/**
 * An adapter for a list of account descriptions.
 */

class AccountProviderDescriptionAdapter(
  private val accounts: List<AccountProviderDescription>,
  private val imageLoader: ImageLoaderType,
  private val onItemClicked: (AccountProviderDescription) -> Unit
) : RecyclerView.Adapter<AccountProviderDescriptionAdapter.AccountViewHolder>() {

  override fun onCreateViewHolder(
    parent: ViewGroup,
    viewType: Int
  ): AccountViewHolder {
    val inflater =
      LayoutInflater.from(parent.context)
    val item =
      inflater.inflate(R.layout.account_cell, parent, false)
    return this.AccountViewHolder(item)
  }

  override fun getItemCount(): Int {
    return this.accounts.size
  }

  override fun onBindViewHolder(
    holder: AccountViewHolder,
    position: Int
  ) {
    val account = this.accounts[position]
    holder.parent.setOnClickListener { this.onItemClicked.invoke(account) }
    holder.accountTitleView.text = account.title
    holder.accountSubtitleView.text = ""

    ImageAccountIcons.loadAccountLogoIntoView(
      loader = this.imageLoader.loader,
      account = account,
      defaultIcon = R.drawable.account_default,
      iconView = holder.accountIcon
    )
  }

  inner class AccountViewHolder(val parent: View) : RecyclerView.ViewHolder(parent) {
    val accountIcon =
      parent.findViewById<ImageView>(R.id.accountCellIcon)
    val accountTitleView =
      parent.findViewById<TextView>(R.id.accountCellTitle)
    val accountSubtitleView =
      parent.findViewById<TextView>(R.id.accountCellSubtitle)
  }
}
