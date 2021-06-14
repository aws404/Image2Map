package space.essem.image2map;

import java.util.concurrent.CompletableFuture;

import java.awt.image.BufferedImage;
import java.util.function.UnaryOperator;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;

public class Image2Map implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger();
    public static Image2MapConfig CONFIG = AutoConfig.register(Image2MapConfig.class, GsonConfigSerializer::new)
            .getConfig();

    private static final SuggestionProvider<ServerCommandSource> DITHER_SUGGEST_PROVIDER = SuggestionProviders.register(new Identifier("dither_types"), (commandContext, suggestionsBuilder) -> CommandSource.suggestMatching(Arrays.stream(DitherMode.values()).map(Enum::name), suggestionsBuilder));

    @Override
    public void onInitialize() {
        LOGGER.info("Loading Image2Map...");

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(CommandManager.literal("mapcreate")
                    .requires(source -> source.hasPermissionLevel(CONFIG.minPermLevel))
                    .then(CommandManager.argument("mode", StringArgumentType.word()).suggests(DITHER_SUGGEST_PROVIDER)
                            .then(CommandManager.argument("path", StringArgumentType.greedyString())
                                    .executes(this::createMap))));
        });
    }

    public enum DitherMode {
        NONE,
        FLOYD;

        public static DitherMode fromString(String string) {
            if (string.equalsIgnoreCase("NONE"))
                return DitherMode.NONE;
            else if (string.equalsIgnoreCase("DITHER") || string.equalsIgnoreCase("FLOYD"))
                    return DitherMode.FLOYD;
            throw new IllegalArgumentException("invalid dither mode");
        }
    }

    private int createMap(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        Vec3d pos = source.getPosition();
        PlayerEntity player = source.getPlayer();
        DitherMode mode;
        String modeStr = StringArgumentType.getString(context, "mode");
        try {
            mode = DitherMode.fromString(modeStr);
        } catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(() -> "Invalid dither mode '" + modeStr + "'").create();
        }
        String input = StringArgumentType.getString(context, "path");

        source.sendFeedback(new LiteralText("Generating image map..."), false);
        BufferedImage image;
        try {
            if (isValid(input)) {
                URL url = new URL(input);
                URLConnection connection = url.openConnection();
                connection.setRequestProperty("User-Agent", "Image2Map mod");
                connection.connect();
                image = ImageIO.read(connection.getInputStream());
            } else if (CONFIG.allowLocalFiles) {
                File file = new File(input);
                image = ImageIO.read(file);
            } else {
                image = null;
            }
        } catch (IOException e) {
            source.sendFeedback(new LiteralText("That doesn't seem to be a valid image."), false);
            return 0;
        }

        if (image == null) {
            source.sendFeedback(new LiteralText("That doesn't seem to be a valid image."), false);
            return 0;
        }

        ItemStack stack = MapRenderer.render(image, mode, source.getWorld(), pos.x, pos.z, player);

        source.sendFeedback(new LiteralText("Done!"), false);
        if (!player.getInventory().insertStack(stack)) {
            ItemEntity itemEntity = new ItemEntity(player.world, player.getPos().x, player.getPos().y,
                    player.getPos().z, stack);
            player.world.spawnEntity(itemEntity);
        }

        return 1;
    }

    private static boolean isValid(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
	
	private static final UnaryOperator<Style> LORE_STYLE = s -> s.withColor(Formatting.GOLD).withItalic(false);

	private ListTag getLore(int width, int height) {
		ListTag posterLore = new ListTag();
		posterLore.add(StringTag.of(Text.Serializer
				.toJson(new LiteralText(String.format("Use me on an item frame grid at least %d by %d big", width, height))
						.styled(LORE_STYLE))));
		posterLore
				.add(StringTag.of(Text.Serializer.toJson(new LiteralText("and I'll make a big image!").styled(LORE_STYLE))));
		return posterLore;
	}

	public static Image2MapConfig CONFIG = AutoConfig.register(Image2MapConfig.class, GsonConfigSerializer::new)
			.getConfig();

	@Override
	public void onInitialize() {
		System.out.println("Loading Image2Map...");

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

	protected static ArgumentBuilder<ServerCommandSource, ?> ditherAndPath(Command<ServerCommandSource> command) {
		return CommandManager.argument("dither", StringArgumentType.word()).suggests(DitherMode.getSuggestor())
				.then(CommandManager.argument("path", StringArgumentType.greedyString()).executes(command));
	}

	public enum DitherMode {
		NONE, FLOYD;

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
			return new DitherModeSuggestionProvider();
		}

		static class DitherModeSuggestionProvider implements SuggestionProvider<ServerCommandSource> {

			@Override
			public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context,
					SuggestionsBuilder builder) {
				String typed = builder.getRemaining().toLowerCase();
				if ("none".startsWith(typed))
					builder.suggest("none");
				if ("dither".startsWith(typed))
					builder.suggest("dither");
				return builder.buildFuture();
			}

		}
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
				ListTag maps = new ListTag();
				for (int y = 0; y < context.getCountY(); y++) {
					ListTag mapsY = new ListTag();
					for (int x = 0; x < context.getCountX(); x++) {
						BufferedImage subImage = img.getSubimage(x * SECTION_SIZE, y * SECTION_SIZE, SECTION_SIZE, SECTION_SIZE);
						ItemStack stack = createMap(source, context.getDither(), subImage);
						if (context.shouldMakePoster() && (context.getCountX() > 1 || context.getCountY() > 1)) {
							mapsY.add(IntTag.of(FilledMapItem.getMapId(stack)));
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
					CompoundTag stackDisplay = stack.getOrCreateSubTag("display");
					String path = context.getPath();
					String fileName = ImageUtils.getImageName(path);
					if (fileName == null)
						fileName = path.length() < 15 ? path : "image";
					stackDisplay.put("Name",
							StringTag.of(String.format("{\"text\":\"Poster for '%s'\",\"italic\":false}", fileName)));
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
		if (!player.inventory.insertStack(stack)) {
			ItemEntity itemEntity = new ItemEntity(player.world, player.getPos().x, player.getPos().y, player.getPos().z,
					stack);
			player.world.spawnEntity(itemEntity);
		}
	}

	public enum ScaleMode {
		FIT, FILL, STRETCH;

		public static ScaleMode fromString(String sMode) {
			switch (sMode.toUpperCase()) {
				case "FIT":
					return FIT;
				case "FILL":
					return FILL;
				case "STRETCH":
					return STRETCH;
				default:
					throw new IllegalArgumentException("input string must be a valid enum value!");
			}
		}

		public static SuggestionProvider<ServerCommandSource> getSuggestor() {
			return new ScaleSuggestionProvider();
		}

		private static class ScaleSuggestionProvider implements SuggestionProvider<ServerCommandSource> {
			@Override
			public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context,
					SuggestionsBuilder builder) {
				String typed = builder.getRemaining().toLowerCase();
				if ("fit".startsWith(typed))
					builder.suggest("fit");
				if ("fill".startsWith(typed))
					builder.suggest("fill");
				if ("stretch".startsWith(typed))
					builder.suggest("stretch");
				return builder.buildFuture();
			}
		}
	}

}
