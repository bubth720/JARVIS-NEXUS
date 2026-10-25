package fr.jarvis.nexus.store;
/** Row valid only during its mapping callback. SQL column indexes are one-based. */
public interface NexusRow {
 String getString(String column); String getString(int column);
 int getInt(String column); int getInt(int column);
 long getLong(String column); long getLong(int column);
 double getDouble(String column); double getDouble(int column);
 Object getObject(String column); Object getObject(int column);
}
