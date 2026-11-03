package com.jubitus.ultreia.job;

import net.minecraft.command.ICommandSender;

import java.lang.ref.WeakReference;

public interface TickedJob {

    int getDim();

    WeakReference<ICommandSender> getSenderRef();

    boolean isDone();

    String statusLine();

    void tick(long budgetNs);

    void shutdown();

    String completionMessage();
}