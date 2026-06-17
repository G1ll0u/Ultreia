package com.jubitus.ultreia.gen;

import net.minecraft.command.ICommandSender;

import java.lang.ref.WeakReference;

public interface MPBAsyncJob {

    int getDim();

    WeakReference<ICommandSender> getSenderRef();

    boolean isDone();

    String statusLine();

    void tick(long budgetNs);

    void shutdown();

    String completionMessage();
}