package cn.niuyannet.kaochang.android.utils;


import static android.content.Context.MODE_PRIVATE;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSON;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.util.List;

import cn.niuyannet.kaochang.android.MyApp;


public class PreferenceUtils {
    /**
     * 保存Boolean类型至SharedPreferences
     *
     * @param file  the file
     * @param key   the key
     * @param value value
     */
    public static void saveBooleanPreference(String file, String key, boolean value) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        SharedPreferences.Editor mEditor = sp.edit();
        mEditor.putBoolean(key, value);
        mEditor.apply();
    }

    /**
     * 从SharedPreferences获取Boolean
     *
     * @param file           the file
     * @param key            the key
     * @param defaultBoolean the default boolean
     * @return the int
     */
    public static boolean getBooleanPreference(String file, String key, boolean defaultBoolean) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        return sp.getBoolean(key, defaultBoolean);
    }


    /**
     * 保存Int类型至SharedPreferences
     *
     * @param file  the file
     * @param key   the key
     * @param value value
     */
    public static void saveIntPreference(String file, String key, int value) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        SharedPreferences.Editor mEditor = sp.edit();
        mEditor.putInt(key, value);
        mEditor.apply();
    }


    /**
     * 保存Int类型至SharedPreferences
     *
     * @param file  the file
     * @param key   the key
     * @param value value
     */
    public static void saveLongPreference(String file, String key, long value) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        SharedPreferences.Editor mEditor = sp.edit();
        mEditor.putLong(key, value);
        mEditor.apply();
    }

    /**
     * 从SharedPreferences获取Float
     *
     * @param file         the file
     * @param key          the key
     * @param defaultFloat the default int
     * @return the int
     */
    public static float getFloatPreference(String file, String key, float defaultFloat) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        return sp.getFloat(key, defaultFloat);
    }

    /**
     * 保存Float类型至SharedPreferences
     *
     * @param file  the file
     * @param key   the key
     * @param value value
     */
    public static void saveFloatPreference(String file, String key, float value) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        SharedPreferences.Editor mEditor = sp.edit();
        mEditor.putFloat(key, value);
        mEditor.apply();
    }

    /**
     * 从SharedPreferences获取Int
     *
     * @param file       the file
     * @param key        the key
     * @param defaultInt the default int
     * @return the int
     */
    public static int getIntPreference(String file, String key, int defaultInt) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        int value = sp.getInt(key, defaultInt);
        return value;
    }

    /**
     * 从SharedPreferences获取Long
     *
     * @param file        the file
     * @param key         the key
     * @param defaultLong the default
     * @return the int
     */
    public static long getLongPreference(String file, String key, long defaultLong) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        long value = sp.getLong(key, defaultLong);
        return value;
    }

    /**
     * 保存String类型至SharedPreferences
     *
     * @param file  the file
     * @param key   the key
     * @param value the value
     */
    public static void saveStringPreference(String file, String key, String value) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        SharedPreferences.Editor mEditor = sp.edit();
        mEditor.putString(key, value);
        mEditor.apply();
    }

    /**
     * 从SharedPreferences获取String
     *
     * @param file          the file
     * @param key           the key
     * @param defaultString the default string
     * @return the string
     */
    public static String getStringPreference(String file, String key, String defaultString) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        String value = sp.getString(key, defaultString);
        return value;
    }


    /**
     * 保存List类型至SharedPreferences
     *
     * @param file the file
     * @param key  the key
     * @param list the list
     */
    public static void saveListPreference(String file, String key, List list) {
        String jsonString = JSON.toJSONString(list);
        saveStringPreference(file, key, jsonString);
    }


    /**
     * 从SharedPreferences获取List
     *
     * @param <T>  the type parameter
     * @param file the file
     * @param key  the key
     * @param c    the c
     * @return the list preference
     */
    public static <T> List<T> getListPreference(String file, String key, Class<T> c) {
        String jsonString = getStringPreference(file, key, "");
        return JSON.parseArray(jsonString, c);
    }


    /**
     * 保存Object至SharedPreferences(16进制字符串形式)
     * Notice:Object请implements Serializable
     *
     * @param file the file
     * @param key  the key
     * @param obj  the obj
     */
    public static void saveObjectPreference(String file, String key, Object obj) {
        try {
            if (obj instanceof Serializable) {
                // 保存对象
                SharedPreferences.Editor sharedata = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE).edit();
                //先将序列化结果写到byte缓存中，其实就分配一个内存空间
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream os = new ObjectOutputStream(bos);
                //将对象序列化写入byte缓存
                os.writeObject(obj);
                //将序列化的数据转为16进制保存
                String bytesToHexString = bytesToHexString(bos.toByteArray());
                //保存该16进制数组
                sharedata.putString(key, bytesToHexString);
                sharedata.apply();
            } else {
                throw new IOException("对象不可序列化");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * 从SharedPreferences获取Object
     *
     * @param file the file
     * @param key  the key
     * @return the object
     */
    @Nullable
    public static <T> T getObjectPreference(String file, String key) {
        try {
            SharedPreferences sharedata = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
            if (sharedata.contains(key)) {
                String string = sharedata.getString(key, "");
                if (TextUtils.isEmpty(string)) {
                    return null;
                } else {
                    //将16进制的数据转为数组，准备反序列化
                    byte[] stringToBytes = StringToBytes(string);
                    ByteArrayInputStream bis = new ByteArrayInputStream(stringToBytes);
                    ObjectInputStream is = new ObjectInputStream(bis);
                    //返回反序列化得到的对象
                    T readObject = (T) is.readObject();
                    return readObject;
                }
            } else {
                return null;
            }
        } catch (StreamCorruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        //所有异常返回null
        return null;
    }

    /**
     * 保存Object至SharedPreferences(16进制字符串形式)
     * Notice:Object请implements Serializable
     *
     * @param file the file
     * @param key  the key
     * @param obj  the obj
     */
    public static void saveObjectBase64Preference(String file, String key, Object obj) {
        try {
            if (obj instanceof Serializable) {
                // 保存对象
                SharedPreferences.Editor sharedata = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE).edit();
                //先将序列化结果写到byte缓存中，其实就分配一个内存空间
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream os = new ObjectOutputStream(bos);
                //将对象序列化写入byte缓存
                os.writeObject(obj);
                //将序列化的数据转为16进制保存
                String s = Base64.encodeToString(bos.toByteArray(), Base64.DEFAULT);
                //保存该16进制数组
                sharedata.putString(key, s);
                sharedata.apply();
            } else {
                throw new IOException("对象不可序列化");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * 从SharedPreferences获取Object
     *
     * @param file the file
     * @param key  the key
     * @return the object
     */
    public static Object getObjectBase64Preference(String file, String key) {
        try {
            SharedPreferences sharedata = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
            if (sharedata.contains(key)) {
                String string = sharedata.getString(key, "");
                if (TextUtils.isEmpty(string)) {
                    return null;
                } else {
                    byte[] stringToBytes = Base64.decode(string, Base64.DEFAULT);
                    ByteArrayInputStream bis = new ByteArrayInputStream(stringToBytes);
                    ObjectInputStream is = new ObjectInputStream(bis);
                    //返回反序列化得到的对象
                    return is.readObject();
                }
            } else {
                return null;
            }
        } catch (StreamCorruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        //所有异常返回null
        return null;
    }


    /**
     * 删除SharedPreferences
     *
     * @param file the file
     */
    public static void deletePreference(String file) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        SharedPreferences.Editor mEditor = sp.edit();
        mEditor.clear();
        mEditor.apply();
    }

    /*
     * 删除SharedPreferences中指定的key
     *
     * @param file the file
     * @param key  the key
     */
    public static void deletePreference(String file, String key) {
        SharedPreferences sp = MyApp.Companion.instance().getApplicationContext().getSharedPreferences(file, MODE_PRIVATE);
        SharedPreferences.Editor mEditor = sp.edit();
        mEditor.remove(key);
        mEditor.apply();
    }


    /**
     * byte数组转16进制字符串
     *
     * @param bArray the b array
     * @return the string
     */
    public static String bytesToHexString(byte[] bArray) {
        if (bArray == null) {
            return null;
        }
        if (bArray.length == 0) {
            return "";
        }
        StringBuffer sb = new StringBuffer(bArray.length);
        String sTemp;
        for (int i = 0; i < bArray.length; i++) {
            sTemp = Integer.toHexString(0xFF & bArray[i]);
            if (sTemp.length() < 2)
                sb.append(0);
            sb.append(sTemp.toUpperCase());
        }
        return sb.toString();
    }


    /**
     * 16进制字符串转byte数组
     *
     * @param data the data
     * @return the byte [ ]
     */
    public static byte[] StringToBytes(String data) {
        String hexString = data.toUpperCase().trim();
        if (hexString.length() % 2 != 0) {
            return null;
        }
        byte[] retData = new byte[hexString.length() / 2];
        for (int i = 0; i < hexString.length(); i++) {
            int int_ch;  // 两位16进制数转化后的10进制数
            char hex_char1 = hexString.charAt(i); ////两位16进制数中的第一位(高位*16)
            int int_ch3;
            if (hex_char1 >= '0' && hex_char1 <= '9')
                int_ch3 = (hex_char1 - 48) * 16;   //// 0 的Ascll - 48
            else if (hex_char1 >= 'A' && hex_char1 <= 'F')
                int_ch3 = (hex_char1 - 55) * 16; //// A 的Ascll - 65
            else
                return null;
            i++;
            char hex_char2 = hexString.charAt(i); ///两位16进制数中的第二位(低位)
            int int_ch4;
            if (hex_char2 >= '0' && hex_char2 <= '9')
                int_ch4 = (hex_char2 - 48); //// 0 的Ascll - 48
            else if (hex_char2 >= 'A' && hex_char2 <= 'F')
                int_ch4 = hex_char2 - 55; //// A 的Ascll - 65
            else
                return null;
            int_ch = int_ch3 + int_ch4;
            retData[i / 2] = (byte) int_ch;//将转化后的数放入Byte里
        }
        return retData;
    }


}

