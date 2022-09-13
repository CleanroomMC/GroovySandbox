package com.cleanroommc.groovysandbox.interception;

import java.util.*;

public enum InterceptionManager {

    INSTANCE;

    private final List<CallInterceptor> callInterceptors = new ArrayList<>();
    private final List<CallInterceptor> unmodifiableCallInterceptors = Collections.unmodifiableList(callInterceptors);
    private final List<String> bannedPackages = new ArrayList<>();
    private final Set<String> bannedClasses = new HashSet<>();
    private final Map<String, Set<String>> bannedMethods = new HashMap<>();
    private final Map<String, Set<String>> bannedFields = new HashMap<>();

    public void initDefaultBans() {
        banPackage("java.io");
        banPackage("java.nio");
        banPackage("java.lang.reflect");
        banPackage("java.lang.invoke");
        banPackage("java.net");
        banPackage("java.rmi");
        banPackage("java.security");
        banPackage("groovy");
        banPackage("org.codehaus.groovy");
        banPackage("sun.");
        banPackage("javax.");
        banPackage("org.spongepowered");
        banPackage("zone.rong.mixinbooter");
        banClass(Runtime.class);
        banClass(ClassLoader.class);
        banMethod(System.class, "exit");
        banMethod(System.class, "gc");
    }

    public void addCallInterceptor(CallInterceptor callInterceptor) {
        this.callInterceptors.add(callInterceptor);
    }

    public void banPackage(String packageName) {
        this.bannedPackages.add(packageName);
    }

    public void banClass(Class<?> clazz) {
        this.bannedClasses.add(clazz.getName());
    }

    public void banMethod(Class<?> clazz, String method) {
        this.bannedMethods.computeIfAbsent(clazz.getName(), key -> new HashSet<>()).add(method);
    }

    public void banField(Class<?> clazz, String field) {
        this.bannedFields.computeIfAbsent(clazz.getName(), key -> new HashSet<>()).add(field);
    }

    public List<CallInterceptor> getCallInterceptors() {
        return unmodifiableCallInterceptors;
    }

    public boolean interceptClass(Class<?> clazz) {
        String packageName = clazz.getPackage().getName();
        List<String> bannedPackages = this.bannedPackages;
        for (int i = 0; i < bannedPackages.size(); i++) {
            if (packageName.startsWith(bannedPackages.get(i))) {
                return true;
            }
        }
        return this.bannedClasses.contains(clazz.getName());
    }

    public boolean interceptClass(String clazz) {
        List<String> bannedPackages = this.bannedPackages;
        for (int i = 0; i < bannedPackages.size(); i++) {
            if (clazz.startsWith(bannedPackages.get(i))) {
                return true;
            }
        }
        return this.bannedClasses.contains(clazz);
    }

}
