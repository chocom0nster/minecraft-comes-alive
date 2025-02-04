package mca.entity;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import mca.api.API;
import mca.api.types.APIButton;
import mca.core.Constants;
import mca.core.MCA;
import mca.core.forge.NetMCA;
import mca.core.minecraft.ItemsMCA;
import mca.core.minecraft.ProfessionsMCA;
import mca.entity.ai.*;
import mca.entity.data.ParentData;
import mca.entity.data.PlayerHistory;
import mca.entity.data.PlayerSaveData;
import mca.entity.data.SavedVillagers;
import mca.entity.inventory.InventoryMCA;
import mca.enums.*;
import mca.items.ItemSpecialCaseGift;
import mca.util.ItemStackCache;
import mca.util.ResourceLocationCache;
import mca.util.Util;
import net.minecraft.block.BlockBed;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.monster.EntityVex;
import net.minecraft.entity.monster.EntityVindicator;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.stats.StatList;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.RegistryNamespaced;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.registry.VillagerRegistry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class EntityVillagerMCA extends EntityVillager {
    public static final int VANILLA_CAREER_ID_FIELD_INDEX = 13;
    public static final int VANILLA_CAREER_LEVEL_FIELD_INDEX = 14;

    public static final DataParameter<String> VILLAGER_NAME = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.STRING);
    public static final DataParameter<String> TEXTURE = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.STRING);
    public static final DataParameter<Integer> GENDER = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
    public static final DataParameter<Float> GIRTH = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.FLOAT);
    public static final DataParameter<Float> TALLNESS = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.FLOAT);
    public static final DataParameter<NBTTagCompound> PLAYER_HISTORY_MAP = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.COMPOUND_TAG);
    public static final DataParameter<Integer> MOVE_STATE = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
    public static final DataParameter<String> SPOUSE_NAME = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.STRING);
    public static final DataParameter<Optional<UUID>> SPOUSE_UUID = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.OPTIONAL_UNIQUE_ID);
    public static final DataParameter<Integer> MARRIAGE_STATE = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
    public static final DataParameter<Boolean> IS_PROCREATING = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.BOOLEAN);
    public static final DataParameter<NBTTagCompound> PARENTS = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.COMPOUND_TAG);
    public static final DataParameter<Boolean> IS_INFECTED = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Integer> AGE_STATE = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
    public static final DataParameter<Integer> ACTIVE_CHORE = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
    public static final DataParameter<Boolean> IS_SWINGING = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Boolean> HAS_BABY = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Boolean> BABY_IS_MALE = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Integer> BABY_AGE = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.VARINT);
    public static final DataParameter<Optional<UUID>> CHORE_ASSIGNING_PLAYER = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.OPTIONAL_UNIQUE_ID);
    public static final DataParameter<BlockPos> BED_POS = EntityDataManager.createKey(EntityVillagerMCA.class, DataSerializers.BLOCK_POS);

    private static final Predicate<EntityVillagerMCA> BANDIT_TARGET_SELECTOR = (v) -> v.getProfessionForge() != ProfessionsMCA.bandit && v.getProfessionForge() != ProfessionsMCA.child;
    private static final Predicate<EntityVillagerMCA> GUARD_TARGET_SELECTOR = (v) -> v.getProfessionForge() == ProfessionsMCA.bandit;

    public final InventoryMCA inventory;
    public int babyAge = 0;
    public UUID playerToFollowUUID = Constants.ZERO_UUID;

    private BlockPos home = BlockPos.ORIGIN;
    private int startingAge = 0;
    private float swingProgressTicks;

    public EntityVillagerMCA() {
        super(null);
        inventory = null;
    }

    public EntityVillagerMCA(World worldIn) {
        super(worldIn);
        inventory = new InventoryMCA(this);
    }

    public EntityVillagerMCA(World worldIn, Optional<VillagerRegistry.VillagerProfession> profession, Optional<EnumGender> gender) {
        this(worldIn);

        if (!worldIn.isRemote) {
            EnumGender eGender = gender.isPresent() ? gender.get() : EnumGender.getRandom();
            set(GENDER, eGender.getId());
            set(VILLAGER_NAME, API.getRandomName(eGender));
            setProfession(profession.isPresent() ? profession.get() : ProfessionsMCA.randomProfession());
            setVanillaCareer(getProfessionForge().getRandomCareer(worldIn.rand));
            set(TEXTURE, API.getRandomSkin(this));

            applySpecialAI();
        }
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        this.dataManager.register(VILLAGER_NAME, "");
        this.dataManager.register(TEXTURE, "");
        this.dataManager.register(GENDER, EnumGender.MALE.getId());
        this.dataManager.register(GIRTH, 0.0F);
        this.dataManager.register(TALLNESS, 0.0F);
        this.dataManager.register(PLAYER_HISTORY_MAP, new NBTTagCompound());
        this.dataManager.register(MOVE_STATE, EnumMoveState.MOVE.getId());
        this.dataManager.register(SPOUSE_NAME, "");
        this.dataManager.register(SPOUSE_UUID, Optional.of(Constants.ZERO_UUID));
        this.dataManager.register(MARRIAGE_STATE, EnumMarriageState.NOT_MARRIED.getId());
        this.dataManager.register(IS_PROCREATING, false);
        this.dataManager.register(PARENTS, new NBTTagCompound());
        this.dataManager.register(IS_INFECTED, false);
        this.dataManager.register(AGE_STATE, EnumAgeState.ADULT.getId());
        this.dataManager.register(ACTIVE_CHORE, EnumChore.NONE.getId());
        this.dataManager.register(IS_SWINGING, false);
        this.dataManager.register(HAS_BABY, false);
        this.dataManager.register(BABY_IS_MALE, false);
        this.dataManager.register(BABY_AGE, 0);
        this.dataManager.register(CHORE_ASSIGNING_PLAYER, Optional.of(Constants.ZERO_UUID));
        this.dataManager.register(BED_POS, BlockPos.ORIGIN);
        this.setSilent(false);
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(MCA.getConfig().villagerMaxHealth);

        if (this.getHealth() <= MCA.getConfig().villagerMaxHealth) {
            this.setHealth(MCA.getConfig().villagerMaxHealth);
        }
    }

    @Override
    protected void initEntityAI() {
        super.initEntityAI();
        this.tasks.addTask(0, new EntityAIProspecting(this));
        this.tasks.addTask(0, new EntityAIHunting(this));
        this.tasks.addTask(0, new EntityAIChopping(this));
        this.tasks.addTask(0, new EntityAIHarvesting(this));
        this.tasks.addTask(0, new EntityAIFishing(this));
        this.tasks.addTask(0, new EntityAIMoveState(this));
        this.tasks.addTask(0, new EntityAIAgeBaby(this));
        this.tasks.addTask(0, new EntityAIProcreate(this));
        this.tasks.addTask(0, new EntityAIVerifyBed(this));
        this.tasks.addTask(10, new EntityAIWatchClosest(this, EntityPlayer.class, 8.0F));
        this.tasks.addTask(10, new EntityAILookIdle(this));
    }

    public <T> T get(DataParameter<T> key) {
        return this.dataManager.get(key);
    }

    public <T> void set(DataParameter<T> key, T value) {
        this.dataManager.set(key, value);
    }

    @Override
    public boolean attackEntityAsMob(@Nonnull Entity entityIn) {
        super.attackEntityAsMob(entityIn);
        return entityIn.attackEntityFrom(DamageSource.causeMobDamage(this), this.getProfessionForge() == ProfessionsMCA.guard ? 9.0F : 2.0F);
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);
        set(VILLAGER_NAME, nbt.getString("name"));
        set(GENDER, nbt.getInteger("gender"));
        set(TEXTURE, nbt.getString("texture"));
        set(GIRTH, nbt.getFloat("girth"));
        set(TALLNESS, nbt.getFloat("tallness"));
        set(PLAYER_HISTORY_MAP, nbt.getCompoundTag("playerHistoryMap"));
        set(MOVE_STATE, nbt.getInteger("moveState"));
        set(MARRIAGE_STATE, nbt.getInteger("marriageState"));
        set(SPOUSE_UUID, Optional.of(nbt.getUniqueId("spouseUUID")));
        set(SPOUSE_NAME, nbt.getString("spouseName"));
        set(IS_PROCREATING, nbt.getBoolean("isProcreating"));
        set(IS_INFECTED, nbt.getBoolean("infected"));
        set(AGE_STATE, nbt.getInteger("ageState"));
        set(ACTIVE_CHORE, nbt.getInteger("activeChore"));
        set(CHORE_ASSIGNING_PLAYER, Optional.of(nbt.getUniqueId("choreAssigningPlayer")));
        set(HAS_BABY, nbt.getBoolean("hasBaby"));
        set(BABY_IS_MALE, nbt.getBoolean("babyIsMale"));
        set(PARENTS, nbt.getCompoundTag("parents"));
        set(BED_POS, new BlockPos(nbt.getInteger("bedX"), nbt.getInteger("bedY"), nbt.getInteger("bedZ")));
        inventory.readInventoryFromNBT(nbt.getTagList("inventory", 10));

        // Vanilla Age doesn't apply from the superclass call. Causes children to revert to the starting age on world reload.
        this.startingAge = nbt.getInteger("startingAge");
        setGrowingAge(nbt.getInteger("Age"));

        this.home = new BlockPos(nbt.getDouble("homePositionX"), nbt.getDouble("homePositionY"), nbt.getDouble("homePositionZ"));
        this.playerToFollowUUID = nbt.getUniqueId("playerToFollowUUID");
        this.babyAge = nbt.getInteger("babyAge");
        applySpecialAI();
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound nbt) {
        super.writeEntityToNBT(nbt);
        nbt.setUniqueId("uuid", this.getUniqueID()); // for SavedVillagers
        nbt.setString("name", get(VILLAGER_NAME));
        nbt.setString("texture", get(TEXTURE));
        nbt.setInteger("gender", get(GENDER));
        nbt.setFloat("girth", get(GIRTH));
        nbt.setFloat("tallness", get(TALLNESS));
        nbt.setTag("playerHistoryMap", get(PLAYER_HISTORY_MAP));
        nbt.setInteger("moveState", get(MOVE_STATE));
        nbt.setInteger("marriageState", get(MARRIAGE_STATE));
        nbt.setDouble("homePositionX", home.getX());
        nbt.setDouble("homePositionY", home.getY());
        nbt.setDouble("homePositionZ", home.getZ());
        nbt.setUniqueId("playerToFollowUUID", playerToFollowUUID);
        nbt.setUniqueId("spouseUUID", get(SPOUSE_UUID).or(Constants.ZERO_UUID));
        nbt.setString("spouseName", get(SPOUSE_NAME));
        nbt.setBoolean("isProcreating", get(IS_PROCREATING));
        nbt.setBoolean("infected", get(IS_INFECTED));
        nbt.setInteger("ageState", get(AGE_STATE));
        nbt.setInteger("startingAge", startingAge);
        nbt.setInteger("activeChore", get(ACTIVE_CHORE));
        nbt.setUniqueId("choreAssigningPlayer", get(CHORE_ASSIGNING_PLAYER).or(Constants.ZERO_UUID));
        nbt.setTag("inventory", inventory.writeInventoryToNBT());
        nbt.setInteger("babyAge", babyAge);
        nbt.setTag("parents", get(PARENTS));
        nbt.setInteger("bedX", get(BED_POS).getX());
        nbt.setInteger("bedY", get(BED_POS).getY());
        nbt.setInteger("bedZ", get(BED_POS).getZ());
    }

    @Override
    protected void damageEntity(@Nonnull DamageSource damageSource, float damageAmount) {
        // Guards take 30% less damage
        if (getProfessionForge() == ProfessionsMCA.guard) {
            damageAmount *= 0.70;
        }
        super.damageEntity(damageSource, damageAmount);

        // Check for infection to apply. Does not affect guards.
        if (MCA.getConfig().enableInfection && getProfessionForge() != ProfessionsMCA.guard && damageSource.getImmediateSource() instanceof EntityZombie && getRNG().nextFloat() < MCA.getConfig().infectionChance / 100) {
            set(IS_INFECTED, true);
        }
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        updateSwinging();

        if (this.isServerWorld()) {
            onEachServerUpdate();
        } else {
            onEachClientUpdate();
        }
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSourceIn) {
        return SoundEvents.ENTITY_GENERIC_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return get(IS_INFECTED) ? SoundEvents.ENTITY_ZOMBIE_DEATH : null;
    }

    @Override
    public boolean processInteract(EntityPlayer player, @Nonnull EnumHand hand) {
        // No-op, handled by EventHooks
        return true;
    }

    @Override
    public void onDeath(@Nonnull DamageSource cause) {
        if (!world.isRemote) {
            if (MCA.getConfig().logVillagerDeaths) {
                String causeName = cause.getImmediateSource() == null ? "Unknown" : cause.getImmediateSource().getName();
                MCA.getLog().info("Villager death: " + get(VILLAGER_NAME) + ". Caused by: " + causeName + ". UUID: " + this.getUniqueID().toString());
            }

            inventory.dropAllItems();

            if (isMarried()) {
                UUID spouseUUID = get(SPOUSE_UUID).or(Constants.ZERO_UUID);
                Optional<EntityVillagerMCA> spouse = Util.getEntityByUUID(world, spouseUUID, EntityVillagerMCA.class);
                PlayerSaveData playerSaveData = PlayerSaveData.getExisting(world, spouseUUID);

                // Notify spouse of the death
                if (spouse.isPresent()) {
                    spouse.get().endMarriage();
                } else if (playerSaveData != null) {
                    playerSaveData.endMarriage();
                    EntityPlayer player = world.getPlayerEntityByUUID(spouseUUID);
                    if (player != null) {
                        player.sendMessage(new TextComponentString(Constants.Color.RED + MCA.getLocalizer().localize("notify.spousedied", get(VILLAGER_NAME), cause.getImmediateSource().getName())));
                    }
                }
            }

            // Notify all parents of the death
            ParentData parents = ParentData.fromNBT(get(PARENTS));
            Arrays.stream(parents.getParentEntities(world))
                    .filter(e -> e instanceof EntityPlayer)
                    .forEach(e -> {
                        EntityPlayer player = (EntityPlayer) e;
                        player.sendMessage(new TextComponentString(Constants.Color.RED + MCA.getLocalizer().localize("notify.childdied", get(VILLAGER_NAME), cause.getImmediateSource().getName())));
                    });

            SavedVillagers.get(world).save(this);
        }
    }

    @Override
    protected void onGrowingAdult() {
        Entity[] parents = ParentData.fromNBT(get(PARENTS)).getParentEntities(world);
        set(AGE_STATE, EnumAgeState.ADULT.getId());
        Arrays.stream(parents).filter((e) -> e instanceof EntityPlayer).forEach((e) -> {
            PlayerHistory history = getPlayerHistoryFor(e.getUniqueID());
            history.setDialogueType(EnumDialogueType.ADULT);
            e.sendMessage(new TextComponentString(MCA.getLocalizer().localize("notify.child.grownup", this.get(VILLAGER_NAME))));
        });

        // set profession away from child for villager children
        if (getProfessionForge() == ProfessionsMCA.child) {
            setProfession(ProfessionsMCA.randomProfession());
            setVanillaCareer(getProfessionForge().getRandomCareer(world.rand));
        }
    }

    @Override
    @Nonnull
    public ITextComponent getDisplayName() {
        // translate profession name
        ITextComponent careerName = new TextComponentTranslation("entity.Villager." + getVanillaCareer().getName());
        EnumAgeState age = EnumAgeState.byId(get(AGE_STATE));
        String professionName = age != EnumAgeState.ADULT ? age.localizedName() : careerName.getUnformattedText();
        String color = this.getProfessionForge() == ProfessionsMCA.bandit ? Constants.Color.RED : this.getProfessionForge() == ProfessionsMCA.guard ? Constants.Color.GREEN : "";

        return new TextComponentString(String.format("%1$s%2$s%3$s (%4$s)", color, MCA.getConfig().villagerChatPrefix, get(VILLAGER_NAME), professionName));
    }

    @Override
    public void swingArm(EnumHand hand) {
        this.setActiveHand(EnumHand.MAIN_HAND);
        super.swingArm(EnumHand.MAIN_HAND);

        if (!get(IS_SWINGING) || swingProgressTicks >= 8 / 2 || swingProgressTicks < 0) {
            swingProgressTicks = -1;
            set(IS_SWINGING, true);
        }
    }

    private void updateSwinging() {
        if (get(IS_SWINGING)) {
            swingProgressTicks++;

            if (swingProgressTicks >= 8) {
                swingProgressTicks = 0;
                set(IS_SWINGING, false);
            }
        } else {
            swingProgressTicks = 0;
        }
        swingProgress = swingProgressTicks / (float) 8;
    }

    @Override
    @Nonnull
    public ItemStack getItemStackFromSlot(EntityEquipmentSlot slotIn) {
        if (slotIn == EntityEquipmentSlot.MAINHAND) {
            VillagerRegistry.VillagerProfession profession = getProfessionForge();
            EnumChore chore = EnumChore.byId(get(ACTIVE_CHORE));
            if (get(HAS_BABY)) {
                return ItemStackCache.get(get(BABY_IS_MALE) ? ItemsMCA.BABY_BOY : ItemsMCA.BABY_GIRL);
            } else if (chore != EnumChore.NONE) {
                return inventory.getBestItemOfType(chore.getToolType());
            } else {
                return ProfessionsMCA.getDefaultHeldItem(profession, getVanillaCareer());
            }
        } else {
            return inventory.getBestArmorOfType(slotIn);
        }
    }

    public void setStartingAge(int value) {
        this.startingAge = value;
        setGrowingAge(value);
    }

    public PlayerHistory getPlayerHistoryFor(UUID uuid) {
        if (!get(PLAYER_HISTORY_MAP).hasKey(uuid.toString())) {
            updatePlayerHistoryMap(PlayerHistory.getNew(this, uuid));
        }
        return PlayerHistory.fromNBT(this, uuid, get(PLAYER_HISTORY_MAP).getCompoundTag(uuid.toString()));
    }

    public void updatePlayerHistoryMap(PlayerHistory history) {
        NBTTagCompound nbt = get(PLAYER_HISTORY_MAP);
        nbt.setTag(history.getPlayerUUID().toString(), history.toNBT());
        set(PLAYER_HISTORY_MAP, nbt);
        this.dataManager.setDirty(PLAYER_HISTORY_MAP);
    }

    public void reset() {
        set(PLAYER_HISTORY_MAP, new NBTTagCompound());
        dataManager.setDirty(PLAYER_HISTORY_MAP);

        setHealth(20.0F);

        set(SPOUSE_NAME, "");
        set(SPOUSE_UUID, Optional.of(Constants.ZERO_UUID));
        set(MARRIAGE_STATE, EnumMarriageState.NOT_MARRIED.getId());
        set(HAS_BABY, false);
    }

    public VillagerRegistry.VillagerCareer getVanillaCareer() {
        return this.getProfessionForge().getCareer(ObfuscationReflectionHelper.getPrivateValue(EntityVillager.class, this, VANILLA_CAREER_ID_FIELD_INDEX));
    }

    public void setVanillaCareer(int careerId) {
        ObfuscationReflectionHelper.setPrivateValue(EntityVillager.class, this, careerId, VANILLA_CAREER_ID_FIELD_INDEX);
    }

    private void setSizeForAge() {
        EnumAgeState age = EnumAgeState.byId(get(AGE_STATE));
        this.setSize(age.getWidth(), age.getHeight());
        this.setScale(1.0F); // trigger rebuild of the bounding box
    }

    private void toggleMount(EntityPlayerMP player) {
        if (getRidingEntity() != null) {
            dismountRidingEntity();
        } else {
            try {
                List<EntityHorse> horses = world.getEntities(EntityHorse.class, h -> (h.isHorseSaddled() && !h.isBeingRidden() && h.getDistance(this) < 3.0D));
                startRiding(horses.stream().min(Comparator.comparingDouble(this::getDistance)).get(), true);
                getNavigator().clearPath();
            } catch (NoSuchElementException e) {
                say(Optional.of(player), "interaction.ridehorse.fail.notnearby");
            }
        }
    }

    private void goHome(EntityPlayerMP player) {
        if (home.equals(Vec3d.ZERO)) {
            say(Optional.of(player), "interaction.gohome.fail");
        } else {
            say(Optional.of(player), "interaction.gohome.success");
            if (!getNavigator().setPath(getNavigator().getPathToXYZ(home.getX(), home.getY(), home.getZ()), 1.0D)) {
                attemptTeleport(home.getX(), home.getY(), home.getZ());
            }
        }
    }

    /**
     * Forces the villager's home to be set to their position. No checks for safety are made.
     * This is used on overwriting the original villager.
     */
    public void forcePositionAsHome() {
        this.home = this.getPosition();
    }

    private void setHome(EntityPlayerMP player) {
        if (attemptTeleport(posX, posY, posZ)) {
            say(Optional.of(player), "interaction.sethome.success");
            this.home = this.getPosition();
            this.setHomePosAndDistance(this.home, 32);
            BlockPos bed = Util.getNearestPoint(this.getPos(), Util.getNearbyBlocks(this.getPos(), world, BlockBed.class, 6, 3));
            if (bed != null) {
                set(BED_POS, bed);
            }

        } else {
            say(Optional.of(player), "interaction.sethome.fail");
        }
    }

    public void say(Optional<EntityPlayer> player, String phraseId, @Nullable String... params) {
        ArrayList<String> paramsList = new ArrayList<>();
        if (params != null) Collections.addAll(paramsList, params);

        if (player.isPresent()) {
            EntityPlayer thePlayer = player.get();

            // Provide player as the first param, always
            paramsList.add(0, thePlayer.getName());

            // Infected villagers do not speak.
            if (get(IS_INFECTED)) {
                thePlayer.sendMessage(new TextComponentString(getDisplayName().getFormattedText() + ": " + "???"));
                this.playSound(SoundEvents.ENTITY_ZOMBIE_AMBIENT, 0.5F, rand.nextFloat() + 0.5F);
            } else {
                String dialogueType = getPlayerHistoryFor(player.get().getUniqueID()).getDialogueType().getId();
                String phrase = MCA.getLocalizer().localize(dialogueType + "." + phraseId, paramsList);
                thePlayer.sendMessage(new TextComponentString(String.format("%1$s: %2$s", getDisplayName().getFormattedText(), phrase)));
            }
        } else {
            MCA.getLog().warn(new Throwable("Say called on player that is not present!"));
        }
    }

    public boolean isMarried() {
        return !get(SPOUSE_UUID).or(Constants.ZERO_UUID).equals(Constants.ZERO_UUID);
    }

    public boolean isMarriedTo(UUID uuid) {
        return get(SPOUSE_UUID).or(Constants.ZERO_UUID).equals(uuid);
    }

    public void marry(EntityPlayer player) {
        set(SPOUSE_UUID, Optional.of(player.getUniqueID()));
        set(SPOUSE_NAME, player.getName());
        set(MARRIAGE_STATE, EnumMarriageState.MARRIED.getId());
    }

    private void endMarriage() {
        set(SPOUSE_UUID, Optional.of(Constants.ZERO_UUID));
        set(SPOUSE_NAME, "");
        set(MARRIAGE_STATE, EnumMarriageState.NOT_MARRIED.getId());
    }

    private void handleInteraction(EntityPlayerMP player, PlayerHistory history, APIButton button) {
        float successChance = 0.85F;
        int heartsBoost = button.getConstraints().contains(EnumConstraint.ADULTS) ? 15 : 5;

        String interactionName = button.getIdentifier().replace("gui.button.", "");

        successChance -= button.getConstraints().contains(EnumConstraint.ADULTS) ? 0.25F : 0.0F;
        successChance += (history.getHearts() / 10.0D) * 0.025F;

        if (MCA.getConfig().enableDiminishingReturns) successChance -= history.getInteractionFatigue() * 0.05F;

        boolean succeeded = rand.nextFloat() < successChance;
        if (MCA.getConfig().enableDiminishingReturns && succeeded) heartsBoost -= history.getInteractionFatigue() * 0.05F;

        history.changeInteractionFatigue(1);
        history.changeHearts(succeeded ? heartsBoost : (heartsBoost * -1));
        String responseId = String.format("%s.%s", interactionName, succeeded ? "success" : "fail");
        say(Optional.of(player), responseId);
    }

    public void handleButtonClick(EntityPlayerMP player, String guiKey, String buttonId) {
        PlayerHistory history = getPlayerHistoryFor(player.getUniqueID());
        java.util.Optional<APIButton> button = API.getButtonById(guiKey, buttonId);
        if (!button.isPresent()) {
            MCA.getLog().warn("Button not found for key and ID: " + guiKey + ", " + buttonId);
        } else if (button.get().isInteraction()) handleInteraction(player, history, button.get());

        switch (buttonId) {
            case "gui.button.move":
                set(MOVE_STATE, EnumMoveState.MOVE.getId());
                this.playerToFollowUUID = Constants.ZERO_UUID;
                break;
            case "gui.button.stay":
                set(MOVE_STATE, EnumMoveState.STAY.getId());
                break;
            case "gui.button.follow":
                set(MOVE_STATE, EnumMoveState.FOLLOW.getId());
                this.playerToFollowUUID = player.getUniqueID();
                break;
            case "gui.button.ridehorse":
                toggleMount(player);
                break;
            case "gui.button.sethome":
                setHome(player);
                break;
            case "gui.button.gohome":
                goHome(player);
                break;
            case "gui.button.trade":
                if (MCA.getConfig().allowTrading) {
                    setCustomer(player);
                    player.displayVillagerTradeGui(this);
                } else {
                    player.sendMessage(new TextComponentString(MCA.getLocalizer().localize("info.trading.disabled")));
                }
                break;
            case "gui.button.inventory":
                player.openGui(MCA.getInstance(), Constants.GUI_ID_INVENTORY, player.world, this.getEntityId(), 0, 0);
                break;
            case "gui.button.gift":
                ItemStack stack = player.inventory.getStackInSlot(player.inventory.currentItem);
                int giftValue = API.getGiftValueFromStack(stack);
                if (!handleSpecialCaseGift(player, stack)) {
                    if (stack.getItem() == Items.GOLDEN_APPLE) set(IS_INFECTED, false);
                    else {
                        history.changeHearts(giftValue);
                        say(Optional.of(player), API.getResponseForGift(stack));
                    }
                }
                if (giftValue > 0) {
                    player.inventory.decrStackSize(player.inventory.currentItem, 1);
                }
                break;
            case "gui.button.procreate":
                if (PlayerSaveData.get(player).isBabyPresent()) say(Optional.of(player), "interaction.procreate.fail.hasbaby");
                else if (history.getHearts() < 100) say(Optional.of(player), "interaction.procreate.fail.lowhearts");
                else {
                    EntityAITasks.EntityAITaskEntry task = tasks.taskEntries.stream().filter((ai) -> ai.action instanceof EntityAIProcreate).findFirst().orElse(null);
                    if (task != null) {
                        ((EntityAIProcreate)task.action).procreateTimer = 20 * 3; // 3 seconds
                        set(IS_PROCREATING, true);
                    }
                }
                break;
            case "gui.button.infected":
                set(IS_INFECTED, !get(IS_INFECTED));
                break;
            case "gui.button.texture.randomize":
                set(TEXTURE, API.getRandomSkin(this));
                break;
            case "gui.button.profession.randomize":
                setProfession(ProfessionsMCA.randomProfession());
                setVanillaCareer(getProfessionForge().getRandomCareer(world.rand));
                break;
            case "gui.button.gender":
                EnumGender gender = EnumGender.byId(get(GENDER));
                if (gender == EnumGender.MALE) {
                    set(GENDER, EnumGender.FEMALE.getId());
                } else {
                    set(GENDER, EnumGender.MALE.getId());
                }
                // intentional fall-through here
            case "gui.button.texture":
                set(TEXTURE, API.getRandomSkin(this));
                break;
            case "gui.button.random":
                set(VILLAGER_NAME, API.getRandomName(EnumGender.byId(get(GENDER))));
                break;
            case "gui.button.profession":
                RegistryNamespaced<ResourceLocation, VillagerRegistry.VillagerProfession> registry = ObfuscationReflectionHelper.getPrivateValue(VillagerRegistry.class, VillagerRegistry.instance(), "REGISTRY");
                setProfession(ProfessionsMCA.randomProfession());
                setVanillaCareer(getProfessionForge().getRandomCareer(world.rand));
                applySpecialAI();
                break;
            case "gui.button.prospecting": startChore(EnumChore.PROSPECT, player); break;
            case "gui.button.hunting": startChore(EnumChore.HUNT, player); break;
            case "gui.button.fishing": startChore(EnumChore.FISH, player); break;
            case "gui.button.chopping": startChore(EnumChore.CHOP, player); break;
            case "gui.button.harvesting": startChore(EnumChore.HARVEST, player); break;
            case "gui.button.stopworking": stopChore(); break;
        }
    }

    private boolean handleSpecialCaseGift(EntityPlayer player, ItemStack stack) {
        Item item = stack.getItem();

        if (item instanceof ItemSpecialCaseGift && !this.isChild()) { // special case gifts are rings so far so prevent giving them to children
            boolean decStackSize = ((ItemSpecialCaseGift) item).handle(player, this);
            if (decStackSize) player.inventory.decrStackSize(player.inventory.currentItem, -1);
            return true;
        } else if (item == Items.CAKE) {
            Optional<Entity> spouse = Util.getEntityByUUID(world, get(SPOUSE_UUID).or(Constants.ZERO_UUID));
            if (spouse.isPresent()) {
                EntityVillagerMCA progressor = this.get(GENDER) == EnumGender.FEMALE.getId() ? this : (EntityVillagerMCA)spouse.get();
                progressor.set(HAS_BABY, true);
                progressor.set(BABY_IS_MALE, rand.nextBoolean());
                progressor.spawnParticles(EnumParticleTypes.HEART);
            } else say(Optional.of(player), "gift.cake.fail");
        } else if (item == Items.GOLDEN_APPLE && this.isChild()) {
            this.addGrowth(((startingAge / 4) / 20 * -1));
            return true;
        }

        return false;
    }

    private void onEachClientUpdate() {
        if (get(IS_PROCREATING)) {
            this.rotationYawHead += 50.0F;
        }

        if (this.ticksExisted % 20 == 0) {
            onEachClientSecond();
        }
    }

    private void onEachClientSecond() {
        this.setSizeForAge();
    }

    private void onEachServerUpdate() {
        if (this.ticksExisted % 20 == 0) { // Every second
            onEachServerSecond();
        }

        if (this.ticksExisted % 200 == 0 && this.getHealth() > 0.0F) { // Every 10 seconds and when we're not already dead
            if (this.getHealth() < this.getMaxHealth()) {
                this.setHealth(this.getHealth() + 1.0F); // heal
            }
        }

        if (isChild()) {
            EnumAgeState current = EnumAgeState.byId(get(AGE_STATE));
            EnumAgeState target = EnumAgeState.byCurrentAge(startingAge, getGrowingAge());
            if (current != target) {
                set(AGE_STATE, target.getId());
            }
        }
    }

    private void onEachServerSecond() {
        NBTTagCompound memories = get(PLAYER_HISTORY_MAP);
        memories.getKeySet().forEach((key) -> PlayerHistory.fromNBT(this, UUID.fromString(key), memories.getCompoundTag(key)).update());

        if (get(HAS_BABY)) {
            set(BABY_AGE, get(BABY_AGE) + 1);

            if (get(BABY_AGE) >= MCA.getConfig().babyGrowUpTime * 60) { // grow up time is in minutes and we measure age in seconds
                EntityVillagerMCA child = new EntityVillagerMCA(world, Optional.absent(), Optional.of(get(BABY_IS_MALE) ? EnumGender.MALE : EnumGender.FEMALE));
                child.set(EntityVillagerMCA.AGE_STATE, EnumAgeState.BABY.getId());
                child.setStartingAge(MCA.getConfig().childGrowUpTime * 60 * 20 * -1);
                child.setScaleForAge(true);
                child.setPosition(this.posX, this.posY, this.posZ);
                child.set(EntityVillagerMCA.PARENTS, ParentData.create(this.getUniqueID(), this.get(SPOUSE_UUID).get(), this.get(VILLAGER_NAME), this.get(SPOUSE_NAME)).toNBT());
                world.spawnEntity(child);

                set(HAS_BABY, false);
                set(BABY_AGE, 0);
            }
        }
    }

    public ResourceLocation getTextureResourceLocation() {
        if (get(IS_INFECTED)) {
            return ResourceLocationCache.getResourceLocationFor(String.format("mca:skins/%s/zombievillager.png", get(GENDER) == EnumGender.MALE.getId() ? "male" : "female"));
        } else {
            return ResourceLocationCache.getResourceLocationFor(get(TEXTURE));
        }
    }

    private void applySpecialAI() {
        if (getProfessionForge() == ProfessionsMCA.bandit) {
            this.targetTasks.taskEntries.clear();
            this.tasks.taskEntries.clear();
            this.tasks.addTask(1, new EntityAIAttackMelee(this, 0.8D, false));
            this.tasks.addTask(2, new EntityAIMoveThroughVillage(this, 0.6D, false));
            this.targetTasks.addTask(0, new EntityAINearestAttackableTarget(this, EntityVillagerMCA.class, 100, false, false, BANDIT_TARGET_SELECTOR));
            this.targetTasks.addTask(1, new EntityAINearestAttackableTarget(this, EntityPlayer.class, true));
        } else if (getProfessionForge() == ProfessionsMCA.guard) {
            this.tasks.addTask(1, new EntityAIAttackMelee(this, 0.8D, false));
            this.tasks.addTask(2, new EntityAIMoveThroughVillage(this, 0.6D, false));
            this.targetTasks.addTask(0, new EntityAINearestAttackableTarget(this, EntityVillagerMCA.class, 100, false, false, GUARD_TARGET_SELECTOR));
            this.targetTasks.addTask(0, new EntityAINearestAttackableTarget(this, EntityZombie.class, 100, false, false, null));
            this.targetTasks.addTask(0, new EntityAINearestAttackableTarget(this, EntityVex.class, 100, false, false, null));
            this.targetTasks.addTask(0, new EntityAINearestAttackableTarget(this, EntityVindicator.class, 100, false, false, null));
            this.targetTasks.addTask(0, new EntityAINearestAttackableTarget(this, EntityMobHostileDay.class, 100, false, false, null));
            this.targetTasks.addTask(0, new EntityAINearestAttackableTarget(this, EntityMobHostileBase.class, 100, false, false, null));
        }
    }

    public void spawnParticles(EnumParticleTypes particleType)
    {
        if (this.world.isRemote) {
            for (int i = 0; i < 5; ++i) {
                double d0 = this.rand.nextGaussian() * 0.02D;
                double d1 = this.rand.nextGaussian() * 0.02D;
                double d2 = this.rand.nextGaussian() * 0.02D;
                this.world.spawnParticle(particleType, this.posX + (double) (this.rand.nextFloat() * this.width * 2.0F) - (double) this.width, this.posY + 1.0D + (double) (this.rand.nextFloat() * this.height), this.posZ + (double) (this.rand.nextFloat() * this.width * 2.0F) - (double) this.width, d0, d1, d2);
            }
        } else {
            NetMCA.INSTANCE.sendToAll(new NetMCA.SpawnParticles(this.getUniqueID(), particleType));
        }
    }

    public void stopChore() {
        set(ACTIVE_CHORE, EnumChore.NONE.getId());
        set(CHORE_ASSIGNING_PLAYER, Optional.of(Constants.ZERO_UUID));
    }

    public void startChore(EnumChore chore, EntityPlayer player) {
        set(ACTIVE_CHORE, chore.getId());
        set(CHORE_ASSIGNING_PLAYER, Optional.of(player.getUniqueID()));
    }

    public boolean playerIsParent(EntityPlayer player) {
        ParentData data = ParentData.fromNBT(get(PARENTS));
        return data.getParent1UUID().equals(player.getUniqueID()) || data.getParent2UUID().equals(player.getUniqueID());
    }

    @Override
    public BlockPos getHomePosition() {
        return home;
    }

    @Override
    public void detachHome() {
        // no-op, skip EntityVillager's detaching homes which messes up MoveTowardsRestriction.
    }

    public String getCurrentActivity() {
        EnumMoveState moveState = EnumMoveState.byId(get(MOVE_STATE));
        if (moveState != EnumMoveState.MOVE) {
            return moveState.getFriendlyName();
        }

        EnumChore chore = EnumChore.byId(get(ACTIVE_CHORE));
        if (chore != EnumChore.NONE) {
            return chore.getFriendlyName();
        }

        return null;
    }
}
