package ejektaflex.bountiful.item

import ejektaflex.bountiful.Bountiful
import ejektaflex.bountiful.api.enum.EnumBountyRarity
import ejektaflex.bountiful.api.ext.sendMessage
import ejektaflex.bountiful.api.item.IItemBounty
import ejektaflex.bountiful.api.logic.BountyNBT
import ejektaflex.bountiful.logic.BountyChecker
import ejektaflex.bountiful.logic.BountyCreator
import ejektaflex.bountiful.logic.BountyData
import net.minecraft.client.util.ITooltipFlag
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.EnumRarity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.util.ActionResult
import net.minecraft.util.EnumHand
import net.minecraft.util.text.TextComponentString
import net.minecraft.world.World


class ItemBounty : Item(), IItemBounty {

    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        if (stack.hasTagCompound()) {
            val bounty = BountyData().apply { deserializeNBT(stack.tagCompound!!) }
            for (line in bounty.tooltipInfo(worldIn!!)) {
                tooltip.add(line)
            }
        }
    }

    override fun getBountyData(stack: ItemStack): BountyData {
        if (stack.hasTagCompound() && stack.item is ItemBounty) {
            return BountyData().apply { deserializeNBT(stack.tagCompound!!) }
        } else {
            throw Exception("${stack.displayName} is not an ItemBounty or has no NBT data!")
        }
    }

    override fun getRarity(stack: ItemStack): EnumRarity {
        return if (stack.hasTagCompound() && stack.tagCompound!!.hasKey(BountyNBT.Rarity.key)) {
            EnumBountyRarity.getRarityFromInt(stack.tagCompound!!.getInteger(BountyNBT.Rarity.key)).itemRarity
        } else {
            super.getRarity(stack)
        }
    }

    override fun removeTimestamp(stack: ItemStack) {
        if (stack.hasTagCompound() && stack.tagCompound!!.hasKey(BountyNBT.BountyStamp.key) && stack.tagCompound!!.hasKey(BountyNBT.BountyTime.key) ) {
            stack.tagCompound!!.apply {
                //setLong(BountyNBT.BountyTime.key, timeLeft(stack))
                //removeTag(BountyNBT.BountyStamp.key)
            }
        }
    }

    private fun tickNumber(stack: ItemStack, amount: Int, key: String): Boolean {
        if (stack.hasTagCompound() && stack.tagCompound!!.hasKey(key)) {
            var time = stack.tagCompound!!.getInteger(key)
            if (time > 0) {
                time -= amount
            }
            if (time < 0) {
                time = 0
            }
            stack.tagCompound!!.setInteger(key, time)
            return (time <= 0)
        }
        return true
    }

    override fun tryExpireBountyTime(stack: ItemStack) {
        if (stack.hasTagCompound() && stack.tagCompound!!.hasKey(BountyNBT.BountyTime.key)) {
            stack.tagCompound!!.setInteger(BountyNBT.BountyTime.key, 0)
        }
    }

    override fun tickBoardTime(stack: ItemStack): Boolean {
        return tickNumber(stack, BountyData.boardTickFreq.toInt(), BountyNBT.BoardStamp.key)
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        return super.getItemStackDisplayName(stack) + if (stack.hasTagCompound() && stack.tagCompound!!.hasKey(BountyNBT.Rarity.key)) {
             " (${EnumBountyRarity.getRarityFromInt(stack.tagCompound!!.getInteger(BountyNBT.Rarity.key))})"
        } else {
            ""
        }
    }

    fun ensureTimerStarted(stack: ItemStack, worldIn: World) {
        if (stack.item is ItemBounty && stack.hasTagCompound() && !stack.tagCompound!!.hasKey(BountyNBT.BountyStamp.key)) {
            stack.tagCompound!!.setLong(BountyNBT.BountyStamp.key, worldIn.totalWorldTime)
        }
    }

    override fun onUpdate(stack: ItemStack, worldIn: World, entityIn: Entity?, itemSlot: Int, isSelected: Boolean) {
        if (worldIn.totalWorldTime % BountyData.bountyTickFreq == 1L) {
            ensureTimerStarted(stack, worldIn)
        }
    }

    override fun ensureBounty(stack: ItemStack, worldIn: World) {
        if (stack.item is ItemBounty) {
            if (!stack.hasTagCompound()) {
                stack.tagCompound = BountyCreator.create().serializeNBT().apply {
                    this.removeTag(BountyNBT.BountyStamp.key)
                    this.setLong(BountyNBT.BoardStamp.key, worldIn.totalWorldTime)
                }
            }
        } else {
            throw Exception("${stack.displayName} is not an ItemBounty, so you cannot generate bounty data for it!")
        }
    }

    // Used to cash in the bounty for a reward
    fun cashIn(player: EntityPlayer, hand: EnumHand, atBoard: Boolean = false): Boolean {
        val bountyItem = player.getHeldItem(hand)
        if (!bountyItem.hasTagCompound()) {
            ensureBounty(player.getHeldItem(hand), player.world)
            return false
        }

        val inv = player.inventory.mainInventory
        val bounty = BountyData().apply { deserializeNBT(bountyItem.tagCompound!!) }

        if (bounty.hasExpired(player.world)) {
            player.sendMessage("§4This bounty has expired.")
            return false
        }

        // Returns prerequisite stacks needed to reduce for reward, or null if they don't have the prereqs
        val invItemsAffected = BountyChecker.hasItems(player, inv, bounty) ?: return false

        //val errMsg = "§4You haven't completed all of the requirements for this bounty."

        val entitiesFulfilled = BountyChecker.hasEntitiesFulfilled(bounty)
        if (!entitiesFulfilled) {
            player.sendMessage("§cYou need to kill more mobs to fulfill this bounty.")
            return false
        }


        return if (!atBoard && Bountiful.config.cashInAtBountyBoard) {
            player.sendMessage(TextComponentString("§aBounty requirements met. Fullfill your bounty by right clicking on a bounty board."))
            false
        } else {
            player.sendMessage(TextComponentString("§aBounty Fulfilled!"))
            // Reduce count of relevant prerequisite stacks
            BountyChecker.takeItems(player, inv, bounty, invItemsAffected)

            // Remove bounty note
            player.setHeldItem(hand, ItemStack.EMPTY)

            // Reward player with rewards
            BountyChecker.rewardItems(player, inv, bounty, bountyItem)

            true
        }


    }

    override fun onItemRightClick(worldIn: World, playerIn: EntityPlayer, handIn: EnumHand): ActionResult<ItemStack> {

        if (worldIn.isRemote) {
            return super.onItemRightClick(worldIn, playerIn, handIn)
        }

        cashIn(playerIn, handIn, atBoard = false)

        return super.onItemRightClick(worldIn, playerIn, handIn)
    }

    // Don't flail arms randomly on NBT update
    override fun shouldCauseReequipAnimation(oldStack: ItemStack, newStack: ItemStack, slotChanged: Boolean): Boolean {
        return slotChanged
    }

}