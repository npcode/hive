package models;

import models.enumeration.ResourceType;

import javax.persistence.*;
import java.util.List;

@Entity
public class Unwatch extends UserAction {
    private static final long serialVersionUID = 1L;

    public static Finder<Long, Unwatch> find = new Finder<>(Long.class, Unwatch.class);

    public static List<Unwatch> findBy(ResourceType resourceType, String resourceId) {
        return findBy(find, resourceType, resourceId);
    }

    public static Unwatch findBy(User watcher, ResourceType resourceType, String resourceId) {
        return findBy(find, watcher, resourceType, resourceId);
    }

    public static List<Unwatch> findBy(User user, ResourceType resourceType) {
        return findBy(find, user, resourceType);
    }
}
