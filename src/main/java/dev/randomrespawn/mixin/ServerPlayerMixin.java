package dev.randomrespawn.mixin;

import dev.randomrespawn.RandomRespawnConfig;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

	// Clear any personal spawn point set by a bed or respawn anchor.
	@Inject(at = @At("HEAD"), method = "setRespawnPosition", cancellable = true)
	private void cancelRespawnPosition(CallbackInfo info) {
		if (RandomRespawnConfig.CONFIG.disableBedRespawn) {
			info.cancel();
		}
	}

}