package com.storynpcs.api.event;

/**
 * Published after the recipe set changes: initial load, definitions reload,
 * or a canonical recipe save/delete. {@code origin} names the trigger
 * ({@code "load"}, {@code "reload"}, {@code "recipe.replace"},
 * {@code "recipe.delete"}).
 */
public record RecipesLoadedEvent(String origin, int recipeCount, int groupCount)
        implements StoryNpcsEvent {}
