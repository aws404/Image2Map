package space.essem.image2map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.suggestion.SuggestionProviders;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import space.essem.image2map.config.Image2MapConfig;
import space.essem.image2map.renderer.MapRenderer;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.function.UnaryOperator;

public class Image2Map implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger();
    public static Image2MapConfig CONFIG = AutoConfig.register(Image2MapConfig.class, GsonConfigSerializer::new)
            .getConfig();


    @Override
    public void onInitialize() {
        LOGGER.info("Loading Image2Map...");

		CommandRegistrationCallback.EVENT.register((dispatcher, _dedicated) -> {
			dispatcher.register(
					CommandManager.literal("mapcreate").requires(source -> source.hasPermissionLevel(CONFIG.minPermLevel))
							.then(CommandManager.argument("path", StringArgumentType.greedyString())
									.executes(ctx -> createMaps(MapGenerationContext.getBasicInfo(ctx, true)))));

			dispatcher.register(
					CommandManager.literal("dithermap").requires(source -> source.hasPermissionLevel(CONFIG.minPermLevel))
							.then(ditherAndPath(ctx -> createMaps(MapGenerationContext.getBasicInfo(ctx)))));

			dispatcher.register(CommandManager.literal("multimap")
					.requires(source -> source.hasPermissionLevel(CONFIG.minPermLevel))
					.then(CommandManager.argument("width", IntegerArgumentType.integer(1, 25)).then(CommandManager
							.argument("height", IntegerArgumentType.integer(1, 25))
							.then(CommandManager.argument("scale", StringArgumentType.word()).suggests(ScaleMode.getSuggestor())
									.then(ditherAndPath(ctx -> createMaps(
											MapGenerationContext.getBasicInfo(ctx).getSize(ctx).getScaleMethod(ctx).makePoster(true))))))));
		});
    }
	
	private static final UnaryOperator<Style> LORE_STYLE = s -> s.withColor(Formatting.GOLD).withItalic(false);

	private NbtList getLore(int width, int height) {
		NbtList posterLore = new NbtList();
		posterLore.add(NbtString.of(Text.Serializer
				.toJson(new LiteralText(String.format("Use me on an item frame grid at least %d by %d big", width, height))
						.styled(LORE_STYLE))));
		posterLore
				.add(NbtString.of(Text.Serializer.toJson(new LiteralText("and I'll make a big image!").styled(LORE_STYLE))));
		return posterLore;
	}

	protected static ArgumentBuilder<ServerCommandSource, ?> ditherAndPath(Command<ServerCommandSource> command) {
		return CommandManager.argument("dither", StringArgumentType.word()).suggests(DitherMode.getSuggestor())
				.then(CommandManager.argument("path", StringArgumentType.greedyString()).executes(command));
	}

	private int createMaps(MapGenerationContext context) throws CommandSyntaxException {
		try {
			ServerCommandSource source = context.getSource();
			source.sendFeedback(new LiteralText("Generating image map..."), false);
			BufferedImage sourceImg = ImageUtils.getImage(context.getPath(), source);
			if (sourceImg == null)
				return 0;
			ServerPlayerEntity player = source.getPlayer();
			new Thread(() -> {
				BufferedImage img = ImageUtils.scaleImage(context.getScaleMode(), context.getCountX(), context.getCountY(),
						sourceImg);
				final int SECTION_SIZE = 128;
				NbtList maps = new NbtList();
				for (int y = 0; y < context.getCountY(); y++) {
					NbtList mapsY = new NbtList();
					for (int x = 0; x < context.getCountX(); x++) {
						BufferedImage subImage = img.getSubimage(x * SECTION_SIZE, y * SECTION_SIZE, SECTION_SIZE, SECTION_SIZE);
						ItemStack stack = createMap(source, context.getDither(), subImage);
						if (context.shouldMakePoster() && (context.getCountX() > 1 || context.getCountY() > 1)) {
							mapsY.add(NbtInt.of(FilledMapItem.getMapId(stack)));
						} else {
							givePlayerMap(player, stack);
						}
					}
					maps.add(mapsY);
				}
				if (context.shouldMakePoster() && (context.getCountX() > 1 || context.getCountY() > 1)) {
					BufferedImage posterImg = ImageUtils.scaleImage(ScaleMode.FIT, 1, 1, img);
					ItemStack stack = createMap(source, context.getDither(), posterImg);
					stack.putSubTag("i2mStoredMaps", maps);
					NbtCompound stackDisplay = stack.getOrCreateSubTag("display");
					String path = context.getPath();
					String fileName = ImageUtils.getImageName(path);
					if (fileName == null)
						fileName = path.length() < 15 ? path : "image";
					stackDisplay.put("Name",
							NbtString.of(String.format("{\"text\":\"Poster for '%s'\",\"italic\":false}", fileName)));
					stackDisplay.put("Lore", getLore(context.getCountX(), context.getCountY()));

					givePlayerMap(player, stack);
				}
				source.sendFeedback(new LiteralText("Done!"), false);
			}).start();
			source.sendFeedback(new LiteralText("Map Creation Queued!"), false);
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
		return 1;
	}

	private ItemStack createMap(ServerCommandSource source, DitherMode mode, BufferedImage image) {
		return MapRenderer.render(image, mode, source.getWorld(), source.getPosition().x, source.getPosition().z);
	}

	private void givePlayerMap(PlayerEntity player, ItemStack stack) {
		if (!player.getInventory().insertStack(stack)) {
			ItemEntity itemEntity = new ItemEntity(player.world, player.getPos().x, player.getPos().y, player.getPos().z,
					stack);
			player.world.spawnEntity(itemEntity);
		}
	}

	public enum DitherMode {
		NONE, FLOYD;

		private static final SuggestionProvider<ServerCommandSource> SUGGEST_PROVIDER = SuggestionProviders.register(new Identifier("dither_mode"), (commandContext, suggestionsBuilder) -> CommandSource.suggestMatching(Arrays.stream(values()).map(ditherMode -> ditherMode.name().toLowerCase()), suggestionsBuilder));

		// The default from string method doesn't quite fit my needs
		public static DitherMode fromString(String string) throws CommandSyntaxException {
			if (string.equalsIgnoreCase("NONE"))
				return DitherMode.NONE;
			else if (string.equalsIgnoreCase("DITHER") || string.equalsIgnoreCase("FLOYD"))
				return DitherMode.FLOYD;
			throw new CommandSyntaxException(
					new SimpleCommandExceptionType(new LiteralMessage("Invalid Dither mode '" + string + "'")),
					new LiteralMessage("Invalid Dither mode '" + string + "'"));
		}

		public static SuggestionProvider<ServerCommandSource> getSuggestor() {
			return SUGGEST_PROVIDER;
		}

	}

	public enum ScaleMode {
		FIT, FILL, STRETCH;

		private static final SuggestionProvider<ServerCommandSource> SUGGEST_PROVIDER = SuggestionProviders.register(new Identifier("scale_mode"), (commandContext, suggestionsBuilder) -> CommandSource.suggestMatching(Arrays.stream(values()).map(scaleMode -> scaleMode.name().toLowerCase()), suggestionsBuilder));

		public static ScaleMode fromString(String sMode) {
			return switch (sMode.toUpperCase()) {
				case "FIT" -> FIT;
				case "FILL" -> FILL;
				case "STRETCH" -> STRETCH;
				default -> throw new IllegalArgumentException("input string must be a valid enum value!");
			};
		}

		public static SuggestionProvider<ServerCommandSource> getSuggestor() {
			return SUGGEST_PROVIDER;
		}
	}

}
