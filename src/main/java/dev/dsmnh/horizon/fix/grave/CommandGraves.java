package dev.dsmnh.horizon.fix.grave;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * {@code /graves}：列出自己尚未取走的墓碑。
 *
 * <p>「是否已取走」只在区块已载入时判定，未载入的按未取走列出，玩家到现场即可确认。</p>
 */
public class CommandGraves extends CommandBase {

    @Override
    public String getCommandName() {
        return "graves";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/graves — 列出自己尚未取走的墓碑";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    /**
     * 放行所有发送者。
     *
     * <p>命令只列出玩家自己的墓碑，不需要权限限制。混合端还会按 {@code bukkit.command.graves}
     * 再校验一次，那条由 {@link CommandPermission} 注册为默认允许。
     * 写法与 ServerUtilities 的 {@code CmdBase.Level.ALL} 一致：直接重写本方法返回 true。</p>
     */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayer)) {
            sender.addChatMessage(new ChatComponentText("[墓碑] 该命令只能由玩家执行"));
            return;
        }

        EntityPlayer player = (EntityPlayer) sender;
        List<GraveTracker.Record> pending = GraveTracker.get().pendingOf(player);

        if (pending.isEmpty()) {
            sender.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.YELLOW + "[墓碑] " + EnumChatFormatting.RESET + "没有未取走的墓碑"));
            return;
        }

        sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.YELLOW + "[墓碑] " + EnumChatFormatting.RESET
                        + "共 " + pending.size() + " 座未取走："));

        for (GraveTracker.Record record : pending) {
            sender.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.GRAY + " - " + EnumChatFormatting.AQUA
                            + GraveTracker.describe(record) + EnumChatFormatting.GRAY
                            + "（" + GraveTracker.age(record) + "）"));
        }
    }
}
