package fr.ivalona.royaume;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class IvalonaRoyaume extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private final Map<UUID, Kingdom> kingdoms = new LinkedHashMap<>();
    private final Map<UUID, UUID> membership = new HashMap<>();
    private final Map<String, UUID> claims = new HashMap<>();
    private final Map<UUID, UUID> invites = new HashMap<>();
    private final Map<UUID, Long> disbandConfirm = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data, messages, settingsCfg, upgrades;

    private static final List<String> SETTING_KEYS = List.of(
            "pvp", "explosions", "doors", "trapdoors", "buttons", "levers", "containers",
            "pistons", "lava", "water", "fire", "entity-interact", "vehicles", "mob-griefing", "outsiders-interact"
    );
    private static final List<String> UPGRADE_KEYS = List.of("claims", "members", "crop-growth", "mob-spawn", "bank-interest");

    @Override public void onEnable() {
        saveDefaultConfig();
        for (String f : List.of("messages.yml", "upgrades.yml", "settings.yml", "gui.yml")) saveResource(f, false);
        reloadFiles();
        if (!setupEconomy()) {
            getLogger().severe("Vault + un provider d'economie sont requis.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        loadData();
        PluginCommand cmd = Objects.requireNonNull(getCommand("ro")); cmd.setExecutor(this); cmd.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) new Papi(this).register();
        long ticks = Math.max(1, getConfig().getLong("storage.autosave-minutes", 5)) * 1200L;
        getServer().getScheduler().runTaskTimer(this, this::saveData, ticks, ticks);
        getServer().getScheduler().runTaskTimer(this, this::applyInterest, 1200L, 1200L);
        getLogger().info("IvalonaRoyaume V2 active - " + kingdoms.size() + " royaume(x), " + claims.size() + " claim(s).");
    }
    @Override public void onDisable() { saveData(); }
    private void reloadFiles(){ reloadConfig(); messages=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"messages.yml")); settingsCfg=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"settings.yml")); upgrades=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"upgrades.yml")); }
    private boolean setupEconomy(){ RegisteredServiceProvider<Economy> r=getServer().getServicesManager().getRegistration(Economy.class); if(r==null)return false; economy=r.getProvider(); return economy!=null; }
    private String color(String s){ return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private String format(String s, Map<String,String> vars){ String out=s; for(var e:vars.entrySet()) out=out.replace("{"+e.getKey()+"}",e.getValue()); return color(out); }
    private void msg(CommandSender p,String key){ msg(p,key,Map.of()); }
    private void msg(CommandSender p,String key,Map<String,String> vars){ String raw=messages.getString(key,key); p.sendMessage(format(messages.getString("prefix","&8[&6Royaume&8] ")+raw,vars)); }
    private String ck(Chunk c){ return c.getWorld().getUID()+":"+c.getX()+":"+c.getZ(); }
    private Kingdom mine(Player p){ UUID id=membership.get(p.getUniqueId()); return id==null?null:kingdoms.get(id); }
    private Kingdom byName(String name){ return kingdoms.values().stream().filter(k->k.name.equalsIgnoreCase(name)).findFirst().orElse(null); }
    private boolean owner(Player p, Kingdom k){ return k!=null && k.owner.equals(p.getUniqueId()); }
    private boolean member(Player p, UUID kid){ return Objects.equals(membership.get(p.getUniqueId()),kid); }
    private boolean requireKingdom(Player p, Kingdom k){ if(k==null){msg(p,"errors.no-kingdom");return false;}return true; }
    private String role(Kingdom k,UUID u){return k.members.getOrDefault(u,"MEMBER");}
    private boolean can(Kingdom k,Player p,String action){ if(owner(p,k))return true; List<String> l=settingsCfg.getStringList("roles."+role(k,p.getUniqueId())); if(l.contains("*")||l.contains(action))return true; msg(p,"errors.no-role-permission"); return false; }
    private boolean setting(Kingdom k,String key){ return k.settings.getOrDefault(key, settingsCfg.getBoolean("defaults."+key,false)); }
    private int claimLimit(Kingdom k){ return (int)upgradeValue(k,"claims",getConfig().getInt("claim.initial-limit",10)); }
    private int memberLimit(Kingdom k){ return (int)upgradeValue(k,"members",getConfig().getInt("members.initial-limit",10)); }
    private double upgradeValue(Kingdom k,String type,double def){ return upgrades.getDouble(type+".levels."+k.upgrades.getOrDefault(type,0)+".value",def); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a){
        if(a.length>0 && a[0].equalsIgnoreCase("admin")) return admin(sender,Arrays.copyOfRange(a,1,a.length));
        if(!(sender instanceof Player p)){ msg(sender,"errors.players-only"); return true; }
        if(a.length==0){ if(runMenuHook(p,"main")) return true; help(p); return true; }
        String sub=a[0].toLowerCase(Locale.ROOT); Kingdom k=mine(p);
        switch(sub){
            case "create" -> create(p,a);
            case "claim" -> claim(p,k);
            case "unclaim" -> unclaim(p,k);
            case "bank" -> bank(p,k,a);
            case "membres","members" -> { if(a.length==1 && runMenuHook(p,"members")) return true; members(p,k); }
            case "invite" -> invite(p,k,a);
            case "accept" -> accept(p);
            case "leave" -> leave(p,k);
            case "kick" -> kick(p,k,a,false);
            case "ban" -> kick(p,k,a,true);
            case "unban" -> unban(p,k,a);
            case "settings" -> settings(p,k,a);
            case "upgrade" -> upgrade(p,k,a);
            case "top" -> { if(a.length==1 && runMenuHook(p,"top")) return true; top(p); }
            case "disband" -> disband(p,k,a);
            case "help" -> help(p);
            default -> help(p);
        }
        return true;
    }

    private boolean runMenuHook(Player p,String hook){ String cmd=getConfig().getString("menu-hooks."+hook,"").trim(); if(cmd.isEmpty())return false; cmd=cmd.replace("%player%",p.getName()); p.performCommand(cmd.startsWith("/")?cmd.substring(1):cmd); return true; }
    private void create(Player p,String[] a){ if(a.length<2){msg(p,"usage.create");return;} if(mine(p)!=null){msg(p,"errors.already-kingdom");return;} String name=String.join(" ",Arrays.copyOfRange(a,1,a.length)); int min=getConfig().getInt("names.min-length",3),max=getConfig().getInt("names.max-length",16); if(name.length()<min||name.length()>max||!name.matches(getConfig().getString("names.regex","[A-Za-z0-9_-]+"))){msg(p,"errors.invalid-name");return;} if(byName(name)!=null){msg(p,"errors.name-used");return;} double cost=getConfig().getDouble("creation-cost",2500); if(!economy.has(p,cost)){msg(p,"errors.not-enough-money",Map.of("amount",money(cost)));return;} economy.withdrawPlayer(p,cost); Kingdom n=new Kingdom(UUID.randomUUID(),name,p.getUniqueId()); n.members.put(p.getUniqueId(),"OWNER"); for(String s:SETTING_KEYS)n.settings.put(s,settingsCfg.getBoolean("defaults."+s,false)); kingdoms.put(n.id,n); membership.put(p.getUniqueId(),n.id); msg(p,"success.created",Map.of("kingdom",name,"amount",money(cost))); saveData(); }
    private void claim(Player p,Kingdom k){ if(!requireKingdom(p,k)||!can(k,p,"CLAIM"))return; if(!getConfig().getStringList("claim.allowed-worlds").isEmpty()&&!getConfig().getStringList("claim.allowed-worlds").contains(p.getWorld().getName())){msg(p,"errors.world-disabled");return;} String key=ck(p.getChunk()); if(claims.containsKey(key)){msg(p,"errors.already-claimed");return;} if(k.claims.size()>=claimLimit(k)){msg(p,"errors.claim-limit");return;} if(getConfig().getBoolean("claim.require-adjacent",true)&&!k.claims.isEmpty()&&!hasAdjacentClaim(k,p.getChunk())){msg(p,"errors.claim-not-adjacent");return;} claims.put(key,k.id);k.claims.add(key);msg(p,"success.claimed",Map.of("current",String.valueOf(k.claims.size()),"max",String.valueOf(claimLimit(k)))); }
    private boolean hasAdjacentClaim(Kingdom k,Chunk c){ World w=c.getWorld(); int x=c.getX(),z=c.getZ(); return List.of(w.getChunkAt(x+1,z),w.getChunkAt(x-1,z),w.getChunkAt(x,z+1),w.getChunkAt(x,z-1)).stream().anyMatch(ch->Objects.equals(claims.get(ck(ch)),k.id)); }
    private void unclaim(Player p,Kingdom k){ if(!requireKingdom(p,k)||!can(k,p,"UNCLAIM"))return;String key=ck(p.getChunk());if(!Objects.equals(claims.get(key),k.id)){msg(p,"errors.not-your-claim");return;}claims.remove(key);k.claims.remove(key);msg(p,"success.unclaimed"); }
    private void bank(Player p,Kingdom k,String[] a){ if(!requireKingdom(p,k))return; if(a.length==1){if(runMenuHook(p,"bank"))return;msg(p,"bank.balance",Map.of("amount",money(k.bank)));return;} if(a[1].equalsIgnoreCase("balance")){msg(p,"bank.balance",Map.of("amount",money(k.bank)));return;} if(a.length<3){msg(p,"usage.bank");return;} double v=parsePositive(a[2]);if(v<=0){msg(p,"errors.invalid-amount");return;} if(a[1].equalsIgnoreCase("deposit")){if(!can(k,p,"BANK_DEPOSIT"))return;if(!economy.has(p,v)){msg(p,"errors.not-enough-money",Map.of("amount",money(v)));return;}economy.withdrawPlayer(p,v);k.bank=Math.min(getConfig().getDouble("bank.max-balance",1e12),k.bank+v);msg(p,"bank.deposited",Map.of("amount",money(v),"balance",money(k.bank)));} else if(a[1].equalsIgnoreCase("withdraw")){if(!can(k,p,"BANK_WITHDRAW"))return;if(k.bank<v){msg(p,"errors.bank-insufficient");return;}k.bank-=v;economy.depositPlayer(p,v);msg(p,"bank.withdrawn",Map.of("amount",money(v),"balance",money(k.bank)));}else msg(p,"usage.bank"); }
    private void invite(Player p,Kingdom k,String[] a){if(!requireKingdom(p,k)||!can(k,p,"INVITE"))return;if(a.length<2){msg(p,"usage.invite");return;}Player t=Bukkit.getPlayerExact(a[1]);if(t==null){msg(p,"errors.player-offline");return;}if(membership.containsKey(t.getUniqueId())){msg(p,"errors.target-has-kingdom");return;}if(k.banned.contains(t.getUniqueId())){msg(p,"errors.target-banned");return;}if(k.members.size()>=memberLimit(k)){msg(p,"errors.member-limit");return;}invites.put(t.getUniqueId(),k.id);msg(p,"success.invited",Map.of("player",t.getName()));msg(t,"success.invite-received",Map.of("player",p.getName(),"kingdom",k.name));}
    private void accept(Player p){if(membership.containsKey(p.getUniqueId())){msg(p,"errors.already-kingdom");return;}UUID id=invites.remove(p.getUniqueId());Kingdom k=id==null?null:kingdoms.get(id);if(k==null){msg(p,"errors.no-invite");return;}if(k.members.size()>=memberLimit(k)){msg(p,"errors.member-limit");return;}k.members.put(p.getUniqueId(),"MEMBER");membership.put(p.getUniqueId(),k.id);msg(p,"success.joined",Map.of("kingdom",k.name));}
    private void leave(Player p,Kingdom k){if(!requireKingdom(p,k))return;if(owner(p,k)){msg(p,"errors.owner-cannot-leave");return;}k.members.remove(p.getUniqueId());membership.remove(p.getUniqueId());msg(p,"success.left");}
    private void kick(Player p,Kingdom k,String[] a,boolean ban){if(!requireKingdom(p,k)||!can(k,p,ban?"BAN":"KICK"))return;if(a.length<2){msg(p,ban?"usage.ban":"usage.kick");return;}OfflinePlayer t=Bukkit.getOfflinePlayer(a[1]);if(t.getUniqueId().equals(k.owner)){msg(p,"errors.cannot-target-owner");return;}if(!k.members.containsKey(t.getUniqueId())){msg(p,"errors.not-member");return;}k.members.remove(t.getUniqueId());membership.remove(t.getUniqueId());if(ban)k.banned.add(t.getUniqueId());msg(p,ban?"success.banned":"success.kicked",Map.of("player",name(t)));}
    private void unban(Player p,Kingdom k,String[] a){if(!requireKingdom(p,k)||!can(k,p,"BAN"))return;if(a.length<2){msg(p,"usage.unban");return;}OfflinePlayer t=Bukkit.getOfflinePlayer(a[1]);k.banned.remove(t.getUniqueId());msg(p,"success.unbanned",Map.of("player",name(t)));}
    private void members(Player p,Kingdom k){if(!requireKingdom(p,k))return;msg(p,"members.header",Map.of("current",String.valueOf(k.members.size()),"max",String.valueOf(memberLimit(k))));for(var e:k.members.entrySet())p.sendMessage(format(messages.getString("members.line","&8- &e{player} &7[{role}]"),Map.of("player",name(Bukkit.getOfflinePlayer(e.getKey())),"role",e.getValue())));msg(p,"members.banned",Map.of("count",String.valueOf(k.banned.size())));}
    private void settings(Player p,Kingdom k,String[] a){if(!requireKingdom(p,k))return;if(a.length==1){if(runMenuHook(p,"settings"))return;msg(p,"settings.header");for(String key:SETTING_KEYS)p.sendMessage(format(messages.getString("settings.line","&8- &e{key}: {value}"),Map.of("key",key,"value",String.valueOf(setting(k,key)))));return;}if(!can(k,p,"SETTINGS"))return;String key=a[1].toLowerCase(Locale.ROOT);if(!SETTING_KEYS.contains(key)){msg(p,"errors.unknown-setting",Map.of("settings",String.join(", ",SETTING_KEYS)));return;}boolean v;if(a.length<3||a[2].equalsIgnoreCase("toggle"))v=!setting(k,key);else if(a[2].equalsIgnoreCase("true")||a[2].equalsIgnoreCase("on")||a[2].equals("1"))v=true;else if(a[2].equalsIgnoreCase("false")||a[2].equalsIgnoreCase("off")||a[2].equals("0"))v=false;else{msg(p,"usage.settings");return;}k.settings.put(key,v);msg(p,"settings.changed",Map.of("key",key,"value",String.valueOf(v)));}
    private void upgrade(Player p,Kingdom k,String[] a){if(!requireKingdom(p,k))return;if(a.length==1){if(runMenuHook(p,"upgrade"))return;msg(p,"upgrade.header");for(String type:UPGRADE_KEYS)p.sendMessage(format(messages.getString("upgrade.line","&8- &e{type}: &f{level}"),Map.of("type",type,"level",String.valueOf(k.upgrades.getOrDefault(type,0)))));return;}if(!can(k,p,"UPGRADE"))return;String type=a[1].toLowerCase(Locale.ROOT);if(!UPGRADE_KEYS.contains(type)){msg(p,"errors.unknown-upgrade");return;}int next=k.upgrades.getOrDefault(type,0)+1;String path=type+".levels."+next;if(!upgrades.contains(path)){msg(p,"errors.max-upgrade");return;}String currency=a.length>=3?a[2].toLowerCase(Locale.ROOT):"money";if(currency.equals("gems")&&upgrades.contains(path+".gems")){int cost=upgrades.getInt(path+".gems");if(!takeGems(p,cost))return;msg(p,"upgrade.purchased-gems",Map.of("type",type,"level",String.valueOf(next),"cost",String.valueOf(cost)));}else{double cost=upgrades.getDouble(path+".money",0);if(getConfig().getBoolean("upgrades.money-from-bank",true)){if(k.bank<cost){msg(p,"errors.bank-insufficient");return;}k.bank-=cost;}else{if(!economy.has(p,cost)){msg(p,"errors.not-enough-money",Map.of("amount",money(cost)));return;}economy.withdrawPlayer(p,cost);}msg(p,"upgrade.purchased",Map.of("type",type,"level",String.valueOf(next),"cost",money(cost)));}k.upgrades.put(type,next);}
    private boolean takeGems(Player p,int amount){if(!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")){msg(p,"errors.gems-unavailable");return false;}String ph=getConfig().getString("gems.balance-placeholder","");if(ph.isBlank()){msg(p,"errors.gems-unavailable");return false;}String raw=PlaceholderAPI.setPlaceholders(p,ph).replaceAll("[^0-9.-]","");double bal;try{bal=Double.parseDouble(raw);}catch(Exception e){msg(p,"errors.gems-unavailable");return false;}if(bal<amount){msg(p,"errors.not-enough-gems",Map.of("amount",String.valueOf(amount)));return false;}String cmd=getConfig().getString("gems.withdraw-command","").replace("%player%",p.getName()).replace("%amount%",String.valueOf(amount));if(cmd.isBlank()){msg(p,"errors.gems-unavailable");return false;}Bukkit.dispatchCommand(Bukkit.getConsoleSender(),cmd);return true;}
    private void top(Player p){msg(p,"top.header");int[] i={0};kingdoms.values().stream().sorted(Comparator.comparingDouble((Kingdom x)->x.bank).reversed()).limit(getConfig().getInt("top-limit",10)).forEach(k->{i[0]++;p.sendMessage(format(messages.getString("top.line","&e#{position} {kingdom} &7- &6{bank}"),Map.of("position",String.valueOf(i[0]),"kingdom",k.name,"bank",money(k.bank))));});}
    private void disband(Player p,Kingdom k,String[] a){if(!requireKingdom(p,k))return;if(!owner(p,k)){msg(p,"errors.owner-only");return;}long now=System.currentTimeMillis(),window=getConfig().getLong("disband-confirm-seconds",30)*1000L;if(a.length<2||!a[1].equalsIgnoreCase("confirm")||now-disbandConfirm.getOrDefault(p.getUniqueId(),0L)>window){disbandConfirm.put(p.getUniqueId(),now);msg(p,"disband.confirm",Map.of("seconds",String.valueOf(window/1000)));return;}removeKingdom(k);disbandConfirm.remove(p.getUniqueId());msg(p,"disband.done");saveData();}
    private void removeKingdom(Kingdom k){for(UUID u:new HashSet<>(k.members.keySet()))membership.remove(u);for(String cl:new HashSet<>(k.claims))claims.remove(cl);kingdoms.remove(k.id);}
    private void help(Player p){for(String line:messages.getStringList("help"))p.sendMessage(color(line));}

    private boolean admin(CommandSender s,String[] a){if(!s.hasPermission("ivalonaroyaume.admin")){msg(s,"errors.no-permission");return true;}if(a.length==0){for(String line:messages.getStringList("admin.help"))s.sendMessage(color(line));return true;}String sub=a[0].toLowerCase(Locale.ROOT);switch(sub){
        case "reload"->{reloadFiles();msg(s,"admin.reloaded");}
        case "save"->{saveData();msg(s,"admin.saved");}
        case "list"->{msg(s,"admin.list-header",Map.of("count",String.valueOf(kingdoms.size())));kingdoms.values().forEach(k->s.sendMessage(color("&8- &e"+k.name+" &7("+k.members.size()+" membres, "+k.claims.size()+" claims, "+money(k.bank)+")")));}
        case "info"->{if(a.length<2){msg(s,"usage.admin-info");break;}Kingdom k=byName(a[1]);if(k==null){msg(s,"errors.kingdom-not-found");break;}msg(s,"admin.info",Map.of("kingdom",k.name,"owner",name(Bukkit.getOfflinePlayer(k.owner)),"members",String.valueOf(k.members.size()),"claims",String.valueOf(k.claims.size()),"bank",money(k.bank)));}
        case "disband"->{if(a.length<2){msg(s,"usage.admin-kingdom");break;}Kingdom k=byName(a[1]);if(k==null){msg(s,"errors.kingdom-not-found");break;}removeKingdom(k);msg(s,"admin.disbanded",Map.of("kingdom",k.name));}
        case "bank"->{if(a.length<4){msg(s,"usage.admin-bank");break;}Kingdom k=byName(a[1]);if(k==null){msg(s,"errors.kingdom-not-found");break;}double v=parsePositive(a[3]);if(v<0){msg(s,"errors.invalid-amount");break;}switch(a[2].toLowerCase()){case "set"->k.bank=v;case "add"->k.bank+=v;case "remove"->k.bank=Math.max(0,k.bank-v);default->{msg(s,"usage.admin-bank");return true;}}msg(s,"admin.bank-changed",Map.of("kingdom",k.name,"bank",money(k.bank)));}
        case "setowner"->{if(a.length<3){msg(s,"usage.admin-setowner");break;}Kingdom k=byName(a[1]);Player t=Bukkit.getPlayerExact(a[2]);if(k==null||t==null){msg(s,k==null?"errors.kingdom-not-found":"errors.player-offline");break;}if(membership.containsKey(t.getUniqueId())&&!Objects.equals(membership.get(t.getUniqueId()),k.id)){msg(s,"errors.target-has-kingdom");break;}k.members.put(k.owner,"OFFICER");k.owner=t.getUniqueId();k.members.put(t.getUniqueId(),"OWNER");membership.put(t.getUniqueId(),k.id);msg(s,"admin.owner-changed");}
        case "addmember"->{if(a.length<3){msg(s,"usage.admin-member");break;}Kingdom k=byName(a[1]);Player t=Bukkit.getPlayerExact(a[2]);if(k==null||t==null){msg(s,k==null?"errors.kingdom-not-found":"errors.player-offline");break;}if(membership.containsKey(t.getUniqueId())){msg(s,"errors.target-has-kingdom");break;}k.members.put(t.getUniqueId(),"MEMBER");membership.put(t.getUniqueId(),k.id);msg(s,"admin.member-added");}
        case "removemember"->{if(a.length<3){msg(s,"usage.admin-member");break;}Kingdom k=byName(a[1]);if(k==null){msg(s,"errors.kingdom-not-found");break;}OfflinePlayer t=Bukkit.getOfflinePlayer(a[2]);if(t.getUniqueId().equals(k.owner)){msg(s,"errors.cannot-target-owner");break;}k.members.remove(t.getUniqueId());membership.remove(t.getUniqueId());msg(s,"admin.member-removed");}
        case "setting"->{if(a.length<4){msg(s,"usage.admin-setting");break;}Kingdom k=byName(a[1]);String key=a[2].toLowerCase();if(k==null){msg(s,"errors.kingdom-not-found");break;}if(!SETTING_KEYS.contains(key)){msg(s,"errors.unknown-setting",Map.of("settings",String.join(", ",SETTING_KEYS)));break;}k.settings.put(key,Boolean.parseBoolean(a[3]));msg(s,"admin.setting-changed");}
        case "upgrade"->{if(a.length<4){msg(s,"usage.admin-upgrade");break;}Kingdom k=byName(a[1]);String type=a[2].toLowerCase();if(k==null){msg(s,"errors.kingdom-not-found");break;}if(!UPGRADE_KEYS.contains(type)){msg(s,"errors.unknown-upgrade");break;}int level;try{level=Integer.parseInt(a[3]);}catch(Exception e){msg(s,"errors.invalid-amount");break;}if(!upgrades.contains(type+".levels."+level)){msg(s,"errors.max-upgrade");break;}k.upgrades.put(type,level);msg(s,"admin.upgrade-changed");}
        case "forceclaim"->{if(!(s instanceof Player p)){msg(s,"errors.players-only");break;}if(a.length<2){msg(s,"usage.admin-kingdom");break;}Kingdom k=byName(a[1]);if(k==null){msg(s,"errors.kingdom-not-found");break;}String key=ck(p.getChunk());UUID old=claims.put(key,k.id);if(old!=null&&kingdoms.get(old)!=null)kingdoms.get(old).claims.remove(key);k.claims.add(key);msg(s,"admin.forceclaimed");}
        case "forceunclaim"->{if(!(s instanceof Player p)){msg(s,"errors.players-only");break;}String key=ck(p.getChunk());UUID old=claims.remove(key);if(old!=null&&kingdoms.get(old)!=null)kingdoms.get(old).claims.remove(key);msg(s,"admin.forceunclaimed");}
        default->{for(String line:messages.getStringList("admin.help"))s.sendMessage(color(line));}
    }return true;}

    // --- Protection des claims ---
    private Kingdom at(Chunk c){UUID id=claims.get(ck(c));return id==null?null:kingdoms.get(id);}
    private boolean deny(Player p,Chunk c,String settingKey){Kingdom k=at(c);if(k==null||member(p,k.id)||p.hasPermission("ivalonaroyaume.bypass"))return false;return !setting(k,settingKey);}
    private void denied(Player p,Cancellable e){e.setCancelled(true);msg(p,"errors.protected");}
    @EventHandler(ignoreCancelled=true) public void onBreak(BlockBreakEvent e){if(deny(e.getPlayer(),e.getBlock().getChunk(),"outsiders-interact"))denied(e.getPlayer(),e);}
    @EventHandler(ignoreCancelled=true) public void onPlace(BlockPlaceEvent e){if(deny(e.getPlayer(),e.getBlock().getChunk(),"outsiders-interact"))denied(e.getPlayer(),e);}
    @EventHandler(ignoreCancelled=true) public void onBucketEmpty(PlayerBucketEmptyEvent e){String key=e.getBucket()==Material.LAVA_BUCKET?"lava":"water";if(deny(e.getPlayer(),e.getBlock().getChunk(),key))denied(e.getPlayer(),e);}
    @EventHandler(ignoreCancelled=true) public void onBucketFill(PlayerBucketFillEvent e){String key=e.getBucket()==Material.LAVA_BUCKET?"lava":"water";if(deny(e.getPlayer(),e.getBlock().getChunk(),key))denied(e.getPlayer(),e);}
    @EventHandler(ignoreCancelled=true) public void onInteract(PlayerInteractEvent e){if(e.getHand()!=null&&e.getHand()!=EquipmentSlot.HAND)return;Block b=e.getClickedBlock();if(b==null)return;String key=interactionKey(b.getType());if(deny(e.getPlayer(),b.getChunk(),key))denied(e.getPlayer(),e);}
    private String interactionKey(Material m){String n=m.name();if(n.contains("TRAPDOOR"))return "trapdoors";if(n.contains("DOOR"))return "doors";if(n.contains("BUTTON")||n.contains("PRESSURE_PLATE"))return "buttons";if(n.contains("LEVER"))return "levers";if(n.contains("CHEST")||n.contains("BARREL")||n.contains("SHULKER")||n.contains("HOPPER")||n.contains("FURNACE")||n.contains("DISPENSER")||n.contains("DROPPER")||n.contains("BREWING")||n.contains("CRAFTER"))return "containers";return "outsiders-interact";}
    @EventHandler(ignoreCancelled=true) public void onEntityInteract(PlayerInteractEntityEvent e){if(deny(e.getPlayer(),e.getRightClicked().getChunk(),"entity-interact"))denied(e.getPlayer(),e);}
    @EventHandler(ignoreCancelled=true) public void onVehicleEnter(VehicleEnterEvent e){if(e.getEntered() instanceof Player p&&deny(p,e.getVehicle().getChunk(),"vehicles"))denied(p,e);}
    @EventHandler(ignoreCancelled=true) public void onVehicleDamage(VehicleDamageEvent e){if(e.getAttacker() instanceof Player p&&deny(p,e.getVehicle().getChunk(),"vehicles"))denied(p,e);}
    @EventHandler(ignoreCancelled=true) public void onPvp(EntityDamageByEntityEvent e){Player a=attacker(e.getDamager());if(a==null||!(e.getEntity() instanceof Player v))return;Kingdom k=at(v.getChunk());if(k!=null&&!setting(k,"pvp")&&!a.hasPermission("ivalonaroyaume.bypass")){e.setCancelled(true);msg(a,"errors.pvp-disabled");}}
    private Player attacker(Entity e){if(e instanceof Player p)return p;if(e instanceof org.bukkit.entity.Projectile pr&&pr.getShooter() instanceof Player p)return p;return null;}
    @EventHandler(ignoreCancelled=true) public void onExplosion(EntityExplodeEvent e){e.blockList().removeIf(b->{Kingdom k=at(b.getChunk());return k!=null&&!setting(k,"explosions");});}
    @EventHandler(ignoreCancelled=true) public void onBlockExplosion(BlockExplodeEvent e){e.blockList().removeIf(b->{Kingdom k=at(b.getChunk());return k!=null&&!setting(k,"explosions");});}
    @EventHandler(ignoreCancelled=true) public void onFire(BlockBurnEvent e){Kingdom k=at(e.getBlock().getChunk());if(k!=null&&!setting(k,"fire"))e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void onIgnite(BlockIgniteEvent e){Kingdom k=at(e.getBlock().getChunk());if(k!=null&&!setting(k,"fire")&&e.getPlayer()==null)e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void onMobGrief(EntityChangeBlockEvent e){if(e.getEntity() instanceof Player)return;Kingdom k=at(e.getBlock().getChunk());if(k!=null&&!setting(k,"mob-griefing"))e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void onPistonExtend(BlockPistonExtendEvent e){protectPiston(e,e.getBlocks(),e.getDirection());}
    @EventHandler(ignoreCancelled=true) public void onPistonRetract(BlockPistonRetractEvent e){protectPiston(e,e.getBlocks(),e.getDirection());}
    private void protectPiston(Cancellable e,List<Block> blocks,org.bukkit.block.BlockFace face){for(Block b:blocks){Kingdom from=at(b.getChunk()),to=at(b.getRelative(face).getChunk());if((from!=null&&!setting(from,"pistons"))||(to!=null&&!setting(to,"pistons"))||!Objects.equals(from==null?null:from.id,to==null?null:to.id)){e.setCancelled(true);return;}}}
    @EventHandler(ignoreCancelled=true) public void onFlow(BlockFromToEvent e){Kingdom from=at(e.getBlock().getChunk()),to=at(e.getToBlock().getChunk());Material type=e.getBlock().getType();String key=type==Material.LAVA?"lava":type==Material.WATER?"water":null;if(key!=null&&to!=null&&!Objects.equals(from==null?null:from.id,to.id)&&!setting(to,key))e.setCancelled(true);}

    // Upgrades de gameplay : accélération locale dans les chunks claim.
    @EventHandler(ignoreCancelled=true) public void onGrow(BlockGrowEvent e){Kingdom k=at(e.getBlock().getChunk());if(k==null)return;double mult=upgradeValue(k,"crop-growth",1.0);if(mult<=1)return;BlockState ns=e.getNewState();if(ns.getBlockData() instanceof Ageable age){int bonus=(int)Math.floor(mult-1);double frac=(mult-1)-bonus;if(ThreadLocalRandom.current().nextDouble()<frac)bonus++;age.setAge(Math.min(age.getMaximumAge(),age.getAge()+bonus));ns.setBlockData(age);}}
    @EventHandler(ignoreCancelled=true) public void onSpawn(CreatureSpawnEvent e){if(e.getSpawnReason()!=CreatureSpawnEvent.SpawnReason.NATURAL)return;Kingdom k=at(e.getLocation().getChunk());if(k==null)return;double mult=Math.min(getConfig().getDouble("upgrades.mob-spawn-max-multiplier",2.0),upgradeValue(k,"mob-spawn",1.0));double extra=mult-1;if(extra<=0)return;if(ThreadLocalRandom.current().nextDouble()<Math.min(1,extra)){getServer().getScheduler().runTask(this,()->{if(e.getEntity().isValid())e.getLocation().getWorld().spawnEntity(e.getLocation(),e.getEntityType(),CreatureSpawnEvent.SpawnReason.CUSTOM);});}}

    private void applyInterest(){long period=Duration.ofDays(getConfig().getLong("bank.interest-period-days",7)).toMillis(),now=System.currentTimeMillis();for(Kingdom k:kingdoms.values()){if(now-k.lastInterest<period)continue;long cycles=Math.min((now-k.lastInterest)/period,getConfig().getLong("bank.max-catchup-cycles",8));double rate=upgradeValue(k,"bank-interest",getConfig().getDouble("bank.base-interest-percent",2))/100.0;for(int i=0;i<cycles;i++)k.bank=Math.min(getConfig().getDouble("bank.max-balance",1e12),k.bank*(1+rate));k.lastInterest+=cycles*period;}}

    private void loadData(){dataFile=new File(getDataFolder(),"data.yml");data=YamlConfiguration.loadConfiguration(dataFile);for(String s:data.getStringList("kingdom-ids")){try{UUID id=UUID.fromString(s);String b="kingdoms."+s+".";Kingdom k=new Kingdom(id,data.getString(b+"name","Royaume"),UUID.fromString(Objects.requireNonNull(data.getString(b+"owner"))));k.bank=data.getDouble(b+"bank");k.lastInterest=data.getLong(b+"last-interest",System.currentTimeMillis());for(String m:data.getStringList(b+"members")){String[] x=m.split(":",2);UUID u=UUID.fromString(x[0]);k.members.put(u,x.length>1?x[1]:"MEMBER");membership.put(u,id);}for(String u:data.getStringList(b+"banned"))k.banned.add(UUID.fromString(u));k.claims.addAll(data.getStringList(b+"claims"));for(String cl:k.claims)claims.put(cl,id);ConfigurationSection us=data.getConfigurationSection(b+"upgrades");if(us!=null)for(String x:us.getKeys(false))k.upgrades.put(x,us.getInt(x));for(String key:SETTING_KEYS)k.settings.put(key,data.getBoolean(b+"settings."+key,settingsCfg.getBoolean("defaults."+key,false)));kingdoms.put(id,k);}catch(Exception ex){getLogger().warning("Royaume invalide dans data.yml: "+s+" - "+ex.getMessage());}}}
    private void saveData(){if(dataFile==null)return;YamlConfiguration y=new YamlConfiguration();List<String> ids=new ArrayList<>();for(Kingdom k:kingdoms.values()){String s=k.id.toString();ids.add(s);String b="kingdoms."+s+".";y.set(b+"name",k.name);y.set(b+"owner",k.owner.toString());y.set(b+"bank",k.bank);y.set(b+"last-interest",k.lastInterest);y.set(b+"members",k.members.entrySet().stream().map(e->e.getKey()+":"+e.getValue()).toList());y.set(b+"banned",k.banned.stream().map(UUID::toString).toList());y.set(b+"claims",new ArrayList<>(k.claims));for(var e:k.upgrades.entrySet())y.set(b+"upgrades."+e.getKey(),e.getValue());for(var e:k.settings.entrySet())y.set(b+"settings."+e.getKey(),e.getValue());}y.set("kingdom-ids",ids);try{y.save(dataFile);}catch(IOException e){getLogger().severe("Sauvegarde impossible: "+e.getMessage());}}
    private double parsePositive(String s){try{double d=Double.parseDouble(s);return Double.isFinite(d)&&d>0?d:-1;}catch(Exception e){return -1;}}
    private String money(double d){return String.format(Locale.US,getConfig().getString("money-format","%,.2f"),d);}
    private String name(OfflinePlayer p){return p.getName()==null?p.getUniqueId().toString().substring(0,8):p.getName();}

    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] a){List<String> out=new ArrayList<>();if(a.length==1){out.addAll(List.of("create","claim","unclaim","bank","membres","invite","accept","leave","kick","ban","unban","settings","upgrade","top","disband","help"));if(sender.hasPermission("ivalonaroyaume.admin"))out.add("admin");}else if(a[0].equalsIgnoreCase("settings")&&a.length==2)out.addAll(SETTING_KEYS);else if(a[0].equalsIgnoreCase("settings")&&a.length==3)out.addAll(List.of("toggle","true","false"));else if(a[0].equalsIgnoreCase("upgrade")&&a.length==2)out.addAll(UPGRADE_KEYS);else if(a[0].equalsIgnoreCase("upgrade")&&a.length==3)out.addAll(List.of("money","gems"));else if(a[0].equalsIgnoreCase("bank")&&a.length==2)out.addAll(List.of("balance","deposit","withdraw"));else if(a[0].equalsIgnoreCase("admin")&&a.length==2)out.addAll(List.of("reload","save","list","info","disband","bank","setowner","addmember","removemember","setting","upgrade","forceclaim","forceunclaim"));else if(a[0].equalsIgnoreCase("admin")&&a.length==3&&List.of("info","disband","bank","setowner","addmember","removemember","setting","upgrade","forceclaim").contains(a[1].toLowerCase()))out.addAll(kingdoms.values().stream().map(k->k.name).toList());return out.stream().filter(x->x.toLowerCase().startsWith(a[a.length-1].toLowerCase())).sorted().toList();}

    static final class Kingdom {final UUID id;UUID owner;final String name;double bank=0;long lastInterest=System.currentTimeMillis();final Map<UUID,String> members=new LinkedHashMap<>();final Set<UUID>banned=new HashSet<>();final Set<String>claims=new HashSet<>();final Map<String,Integer>upgrades=new HashMap<>();final Map<String,Boolean>settings=new HashMap<>();Kingdom(UUID i,String n,UUID o){id=i;name=n;owner=o;}}
    static final class Papi extends PlaceholderExpansion {final IvalonaRoyaume pl;Papi(IvalonaRoyaume p){pl=p;}public String getIdentifier(){return "ivalonaroyaume";}public String getAuthor(){return "Ivalona";}public String getVersion(){return pl.getPluginMeta().getVersion();}public boolean persist(){return true;}public String onPlaceholderRequest(Player p,String id){if(p==null)return "";Kingdom k=pl.mine(p);if(id.equals("has_kingdom"))return String.valueOf(k!=null);if(k==null)return "";if(id.startsWith("setting_"))return String.valueOf(pl.setting(k,id.substring(8).replace('_','-')));if(id.startsWith("upgrade_"))return String.valueOf(k.upgrades.getOrDefault(id.substring(8).replace('_','-'),0));return switch(id){case "name"->k.name;case "owner"->pl.name(Bukkit.getOfflinePlayer(k.owner));case "rank"->k.members.getOrDefault(p.getUniqueId(),"");case "members"->String.valueOf(k.members.size());case "max_members"->String.valueOf(pl.memberLimit(k));case "claims"->String.valueOf(k.claims.size());case "max_claims"->String.valueOf(pl.claimLimit(k));case "bank"->String.valueOf(k.bank);case "bank_formatted"->pl.money(k.bank);case "bank_interest"->String.valueOf(pl.upgradeValue(k,"bank-interest",pl.getConfig().getDouble("bank.base-interest-percent",2)));case "crop_growth"->String.valueOf(pl.upgradeValue(k,"crop-growth",1));case "mob_spawn"->String.valueOf(pl.upgradeValue(k,"mob-spawn",1));default->"";};}}
}
