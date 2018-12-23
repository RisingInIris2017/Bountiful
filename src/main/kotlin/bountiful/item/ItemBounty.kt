package bountiful.item

import bountiful.Bountiful
import bountiful.logic.BountyCreator
import bountiful.logic.BountyData
import net.minecraft.client.util.ITooltipFlag
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.EnumRarity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.ActionResult
import net.minecraft.util.EnumActionResult
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.TextComponentString
import net.minecraft.world.World
import net.minecraftforge.items.ItemHandlerHelper
import kotlin.math.min

class ItemBounty : Item() {

    init {
        unlocalizedName = "bountiful.bounty"
    }

    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        if (stack.hasTagCompound()) {
            val bounty = BountyData().apply { deserializeNBT(stack.tagCompound!!) }
            if (bounty.time <= 0) {
                tooltip.add("Expired.")
            } else {
                for (line in bounty.toString().split("\n")) {
                    tooltip.add(line)
                }
            }
        }
    }

    override fun getRarity(stack: ItemStack): EnumRarity {
        return if (stack.hasTagCompound() && stack.tagCompound!!.hasKey("rarity")) {
            BountyCreator.getRarityFromInt(stack.tagCompound!!.getInteger("rarity")).itemRarity
        } else {
            super.getRarity(stack)
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

    private fun tryExpireBoardTime(stack: ItemStack) {
        if (stack.hasTagCompound() && stack.tagCompound!!.hasKey("boardTime")) {
            stack.tagCompound!!.setInteger("boardTime", 0)
        }
    }

    // Decrements the amount of time left on the bounty. Returns true if it's run out.
    private fun tickBountyTime(stack: ItemStack, amt: Int): Boolean {
        return tickNumber(stack, amt, "time")
    }

    fun tickBoardTime(stack: ItemStack, amt: Int): Boolean {
        return tickNumber(stack, amt, "boardTime")
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        return super.getItemStackDisplayName(stack) + if (stack.hasTagCompound() && stack.tagCompound!!.hasKey("rarity")) {
             " (${BountyCreator.getRarityFromInt(stack.tagCompound!!.getInteger("rarity"))})"
        } else {
            ""
        }
    }

    override fun onUpdate(stack: ItemStack, worldIn: World, entityIn: Entity, itemSlot: Int, isSelected: Boolean) {
        if (worldIn.totalWorldTime % BountyData.bountyTickFreq == 0L) {
            val expired = tickBountyTime(stack, BountyData.bountyTickFreq.toInt())
            tryExpireBoardTime(stack)
            // Remove itemstack when expired
            if (expired) {
                stack.count = 0
            }
        }
    }

    fun ensureBounty(stack: ItemStack): ItemBounty {
        if (!stack.hasTagCompound()) {
            stack.tagCompound = BountyCreator.create().serializeNBT()
        }
        return this
    }

    // Used to cash in the bounty for a reward
    fun cashIn(player: EntityPlayer, hand: EnumHand, atBoard: Boolean = false): Boolean {
        val bountyItem = player.getHeldItem(hand)
        if (!bountyItem.hasTagCompound()) {
            ensureBounty(player.getHeldItem(hand))
            return false
        }

        val inv = player.inventory.mainInventory
        val bounty = BountyData().apply { deserializeNBT(bountyItem.tagCompound!!) }

        val prereqItems = inv.filter { invItem ->
            bounty.toGet.any { gettable ->
                gettable.first.isItemEqualIgnoreDurability(invItem)
            }
        }

        println("Prereq items: $prereqItems")

        // Check to see if bounty meets all prerequisites
        val hasAllGets = bounty.toGet.all { gettable ->
            val stacksToChange = prereqItems.filter { it.isItemEqualIgnoreDurability(gettable.first) }
            val stackSum = stacksToChange.sumBy { it.count }
            val amountNeeded = gettable.second
            val hasEnough = stackSum >= amountNeeded
            if (!hasEnough) {
                player.sendMessage(TextComponentString("§cCannot fullfill bounty, you don't have everything needed!"))
            }

            hasEnough
        }


        if (hasAllGets) {
            if (!atBoard && Bountiful.config.cashInAtBountyBoard) {
                player.sendMessage(TextComponentString("§aBounty requirements met. Fullfill your bounty by right clicking on a bounty board."))
                return false
            } else {
                player.sendMessage(TextComponentString("§aBounty Fulfilled!"))
            }

            // If it does, reduce count of all relevant stacks
            bounty.toGet.forEach { gettable ->
                var amountNeeded = gettable.second
                val stacksToChange = prereqItems.filter { it.isItemEqualIgnoreDurability(gettable.first) }
                for (stack in stacksToChange) {
                    val amountToRemove = min(stack.count, amountNeeded)
                    stack.count -= amountToRemove
                    amountNeeded -= amountToRemove
                }
            }

            // Remove bounty note
            player.setHeldItem(hand, ItemStack.EMPTY)

            // Reward player with rewards
            bounty.rewards.forEach { reward ->
                var amountNeededToGive = reward.second
                val stacksToGive = mutableListOf<ItemStack>()
                while (amountNeededToGive > 0) {
                    val stackSize = min(amountNeededToGive, maxStackSize)
                    val newStack = reward.first.copy().apply { count = stackSize }
                    stacksToGive.add(newStack)
                    amountNeededToGive -= stackSize
                }
                stacksToGive.forEach { stack ->
                    ItemHandlerHelper.giveItemToPlayer(player, stack)
                }
            }
            return true
        } else {
            return false
        }
    }

    override fun onItemRightClick(worldIn: World, playerIn: EntityPlayer, handIn: EnumHand): ActionResult<ItemStack> {

        if (worldIn.isRemote) {
            return super.onItemRightClick(worldIn, playerIn, handIn)
        }

        cashIn(playerIn, handIn, false)

        return super.onItemRightClick(worldIn, playerIn, handIn)
    }

    // Don't flail arms randomly on NBT update
    override fun shouldCauseReequipAnimation(oldStack: ItemStack, newStack: ItemStack, slotChanged: Boolean): Boolean {
        return slotChanged
    }

}