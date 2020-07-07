package snow.player.playlist;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.tencent.mmkv.MMKV;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import snow.player.media.MusicItem;

/**
 * 用于管理音乐播放器的播放队列。
 */
public abstract class PlaylistManager {
    private static final String KEY_PLAYLIST = "playlist";
    private static final String KEY_PLAYLIST_SIZE = "playlist_size";

    private MMKV mMMKV;
    private Executor mExecutor;
    private boolean mEditable;

    private Handler mMainHandler;

    @Nullable
    private OnModifyPlaylistListener mModifyPlaylistListener;

    /**
     * 创建一个 {@link PlaylistManager} 对象。
     *
     * @param context    {@link Context} 对象，不能为 null
     * @param playlistId 播放列表的 ID，不能为 null。该 ID 会用于持久化保存播放列表，请保证该 ID 的唯一性。
     *                   通常使用 {@link snow.player.PlayerService} 的 {@link Class} 对象的
     *                   {@link Class#getName()} 作为 ID
     */
    public PlaylistManager(@NonNull Context context, @NonNull String playlistId) {
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(playlistId);

        MMKV.initialize(context);
        mMMKV = MMKV.mmkvWithID(playlistId, MMKV.MULTI_PROCESS_MODE);
        mExecutor = Executors.newSingleThreadExecutor();
        mEditable = false;
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 判断当前 PlaylistManager 是否是可编辑的。
     *
     * @return 如果当前 PlaylistManager 是可编辑的，则返回 true，否则返回 false。当返回 false 时，
     * 对 Playlist 的一切修改操作都会被忽略（可以正常访问）。
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public final boolean isEditable() {
        return mEditable;
    }

    /**
     * 设置当前播放列表是否是可编辑的。
     * <p>
     * 这是一个 {@code protected} 方法，不可直接访问，需要继承 {@link PlaylistManager} 类才能访问到该方法。
     */
    protected void setEditable(boolean editable) {
        mEditable = editable;
    }

    /**
     * 设置一个 {@link OnModifyPlaylistListener} 监听器，该监听器会在使用当前 PlaylistManager 修改播放队列
     * 时被调用。
     *
     * @param listener {@link OnModifyPlaylistListener} 监听器，为 null 时相当于青春已设置的监听器
     */
    public void setOnModifyPlaylistListener(@Nullable OnModifyPlaylistListener listener) {
        mModifyPlaylistListener = listener;
    }

    /**
     * 获取当前播放队列的大小。
     * <p>
     * 这是个轻量级操作，可在 UI 线程上直接运行。
     *
     * @return 当前播放队列的大小。
     */
    public int getPlaylistSize() {
        return mMMKV.decodeInt(KEY_PLAYLIST_SIZE, 0);
    }

    /**
     * 获取当前播放队列。
     * <p>
     * 注意！该方法会进行 I/O 操作，因此不建议在 UI 线程中执行。
     *
     * @return 当前播放队列。
     */
    @NonNull
    public Playlist getPlaylist() {
        Playlist playlist = mMMKV.decodeParcelable(KEY_PLAYLIST, Playlist.class);
        if (playlist == null) {
            return new Playlist(new ArrayList<MusicItem>());
        }

        return playlist;
    }

    /**
     * 以异步的方式获取当前播放队列。
     */
    public void getPlaylistAsync(final Callback callback) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                callback.onFinished(getPlaylist());
            }
        });
    }

    /**
     * 设置新的播放列表，并将播放队列的播放位置设为 position 值，同时设置是否在 prepare 完成后自动播放音乐。
     *
     * @param playlist 新的播放列表（不能为 null）
     * @param position 要设置的播放位置值（小于 0 时，相当于设为 0）
     * @param play     否在自动播放 position 位置处的音乐
     */
    public void setPlaylist(@NonNull final Playlist playlist,
                            final int position,
                            final boolean play) {
        Preconditions.checkNotNull(playlist);

        if (!isEditable()) {
            return;
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                save(playlist);
                notifyOnSetNewPlaylist(Math.max(position, 0), play);
            }
        });
    }

    public void insertMusicItem(final int position, @NonNull final MusicItem musicItem) {
        Preconditions.checkNotNull(musicItem);

        if (!isEditable()) {
            return;
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<MusicItem> musicItems = getPlaylist().getAllMusicItem();
                if (musicItems.contains(musicItem)) {
                    moveMusicItem(musicItems.indexOf(musicItem), position);
                    return;
                }

                musicItems.add(position, musicItem);

                save(new Playlist(musicItems));
                notifyMusicItemInserted(position, musicItem);
            }
        });
    }

    public void moveMusicItem(final int fromPosition, final int toPosition) {
        if (fromPosition == toPosition) {
            return;
        }

        if (!isEditable()) {
            return;
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<MusicItem> musicItems = getPlaylist().getAllMusicItem();

                MusicItem from = musicItems.remove(fromPosition);
                musicItems.add(toPosition, from);

                save(new Playlist(musicItems));
                notifyMusicItemMoved(fromPosition, toPosition);
            }
        });
    }

    public void removeMusicItem(@NonNull final MusicItem musicItem) {
        Preconditions.checkNotNull(musicItem);

        if (!isEditable()) {
            return;
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<MusicItem> musicItems = getPlaylist().getAllMusicItem();
                if (!musicItems.contains(musicItem)) {
                    return;
                }

                musicItems.remove(musicItem);

                save(new Playlist(musicItems));
                notifyMusicItemRemoved(musicItem);
            }
        });
    }

    private void save(@NonNull Playlist playlist) {
        Preconditions.checkNotNull(playlist);
        mMMKV.encode(KEY_PLAYLIST, playlist);
        mMMKV.encode(KEY_PLAYLIST_SIZE, playlist.size());
    }

    private void notifyOnSetNewPlaylist(final int position, final boolean play) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mModifyPlaylistListener != null) {
                    mModifyPlaylistListener.onNewPlaylist(position, play);
                }
            }
        });
    }

    private void notifyMusicItemMoved(final int fromPosition, final int toPosition) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mModifyPlaylistListener != null) {
                    mModifyPlaylistListener.onMusicItemMoved(fromPosition, toPosition);
                }
            }
        });
    }

    private void notifyMusicItemInserted(final int position, final MusicItem musicItem) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mModifyPlaylistListener != null) {
                    mModifyPlaylistListener.onMusicItemInserted(position, musicItem);
                }
            }
        });
    }

    private void notifyMusicItemRemoved(final MusicItem musicItem) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mModifyPlaylistListener != null) {
                    mModifyPlaylistListener.onMusicItemRemoved(musicItem);
                }
            }
        });
    }

    public interface Callback {
        void onFinished(@NonNull Playlist playlist);
    }

    public interface OnModifyPlaylistListener {
        void onNewPlaylist(int position, boolean play);

        void onMusicItemMoved(int fromPosition, int toPosition);

        void onMusicItemInserted(int position, MusicItem musicItem);

        void onMusicItemRemoved(MusicItem musicItem);
    }
}
