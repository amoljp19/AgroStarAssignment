/*
 * Copyright 2017 Rozdoum
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.softaai.agrostarassigment.managers;

import android.content.Context;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.softaai.agrostarassigment.ApplicationHelper;
import com.softaai.agrostarassigment.R;
import com.softaai.agrostarassigment.enums.UploadImagePrefix;
import com.softaai.agrostarassigment.managers.listeners.OnDataChangedListener;
import com.softaai.agrostarassigment.managers.listeners.OnObjectExistListener;
import com.softaai.agrostarassigment.managers.listeners.OnPostChangedListener;
import com.softaai.agrostarassigment.managers.listeners.OnPostCreatedListener;
import com.softaai.agrostarassigment.managers.listeners.OnPostListChangedListener;
import com.softaai.agrostarassigment.managers.listeners.OnTaskCompleteListener;
import com.softaai.agrostarassigment.model.Like;
import com.softaai.agrostarassigment.model.Post;
import com.softaai.agrostarassigment.utils.ImageUtil;
import com.softaai.agrostarassigment.utils.LogUtil;

/**
 * Created by Kristina on 10/28/16.
 */

public class PostManager extends FirebaseListenersManager {

    private static final String TAG = PostManager.class.getSimpleName();
    private static PostManager instance;
    private int newPostsCounter = 0;
    private PostCounterWatcher postCounterWatcher;

    private Context context;

    public static PostManager getInstance(Context context) {
        if (instance == null) {
            instance = new PostManager(context);
        }

        return instance;
    }

    private PostManager(Context context) {
        this.context = context;
    }

    public void createOrUpdatePost(Post post) {
        try {
            ApplicationHelper.getDatabaseHelper().createOrUpdatePost(post);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    public void getPostsList(OnPostListChangedListener<Post> onDataChangedListener, long date) {
        ApplicationHelper.getDatabaseHelper().getPostList(onDataChangedListener, date);
    }

    public void getPostsListByUser(OnDataChangedListener<Post> onDataChangedListener, String userId) {
        ApplicationHelper.getDatabaseHelper().getPostListByUser(onDataChangedListener, userId);
    }

    public void getPost(Context context, String postId, OnPostChangedListener onPostChangedListener) {
        ValueEventListener valueEventListener = ApplicationHelper.getDatabaseHelper().getPost(postId, onPostChangedListener);
        addListenerToMap(context, valueEventListener);
    }

    public void getSinglePostValue(String postId, OnPostChangedListener onPostChangedListener) {
        ApplicationHelper.getDatabaseHelper().getSinglePost(postId, onPostChangedListener);
    }

    public void createOrUpdatePostWithImage(Uri imageUri, final OnPostCreatedListener onPostCreatedListener, final Post post) {
        // Register observers to listen for when the download is done or if it fails
        DatabaseHelper databaseHelper = ApplicationHelper.getDatabaseHelper();
        if (post.getId() == null) {
            post.setId(databaseHelper.generatePostId());
        }

        final String imageTitle = ImageUtil.generateImageTitle(UploadImagePrefix.POST, post.getId());

        final StorageReference storageReference = databaseHelper.uploadImage(imageTitle);
        StorageMetadata metadata = databaseHelper.getStorageMetaData();
        UploadTask uploadTask = storageReference.putFile(imageUri, metadata);


        if (uploadTask != null) {

            uploadTask.continueWithTask(new Continuation<UploadTask.TaskSnapshot, Task<Uri>>() {
                @Override
                public Task<Uri> then(@NonNull Task<UploadTask.TaskSnapshot> task) throws Exception {
                    if (!task.isSuccessful()) {
                        throw task.getException();
                    }
                    return storageReference.getDownloadUrl();
                }
            }).addOnCompleteListener(new OnCompleteListener<Uri>() {
                @Override
                public void onComplete(@NonNull Task<Uri> task) {
                    if (task.isSuccessful()) {
                        Uri downloadUri = task.getResult();
                        LogUtil.logDebug(TAG, "successful upload image, image url: " + String.valueOf(downloadUri));

                        post.setImagePath(String.valueOf(downloadUri));
                        post.setImageTitle(imageTitle);
                        createOrUpdatePost(post);
                        onPostCreatedListener.onPostSaved(true);
                    } else {
                        onPostCreatedListener.onPostSaved(false);
                    }
                }
            });
        }
    }

    public Task<Void> removeImage(String imageTitle) {
        final DatabaseHelper databaseHelper = ApplicationHelper.getDatabaseHelper();
        return databaseHelper.removeImage(imageTitle);
    }

    public void removePost(final Post post, final OnTaskCompleteListener onTaskCompleteListener) {
        final DatabaseHelper databaseHelper = ApplicationHelper.getDatabaseHelper();
        Task<Void> removeImageTask = removeImage(post.getImageTitle());

        removeImageTask.addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                databaseHelper.removePost(post).addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        onTaskCompleteListener.onTaskComplete(task.isSuccessful());
                        databaseHelper.updateProfileLikeCountAfterRemovingPost(post);
                        LogUtil.logDebug(TAG, "removePost(), is success: " + task.isSuccessful());
                    }
                });
                LogUtil.logDebug(TAG, "removeImage(): success");
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception exception) {
                LogUtil.logError(TAG, "removeImage()", exception);
                onTaskCompleteListener.onTaskComplete(false);
            }
        });
    }

    public void addComplain(Post post) {
        DatabaseHelper databaseHelper = ApplicationHelper.getDatabaseHelper();
        databaseHelper.addComplainToPost(post);
    }

    public void hasCurrentUserLike(Context activityContext, String postId, String userId, final OnObjectExistListener<Like> onObjectExistListener) {
        DatabaseHelper databaseHelper = ApplicationHelper.getDatabaseHelper();
        ValueEventListener valueEventListener = databaseHelper.hasCurrentUserLike(postId, userId, onObjectExistListener);
        addListenerToMap(activityContext, valueEventListener);
    }

    public void hasCurrentUserLikeSingleValue(String postId, String userId, final OnObjectExistListener<Like> onObjectExistListener) {
        DatabaseHelper databaseHelper = ApplicationHelper.getDatabaseHelper();
        databaseHelper.hasCurrentUserLikeSingleValue(postId, userId, onObjectExistListener);
    }

    public void isPostExistSingleValue(String postId, final OnObjectExistListener<Post> onObjectExistListener) {
        DatabaseHelper databaseHelper = ApplicationHelper.getDatabaseHelper();
        databaseHelper.isPostExistSingleValue(postId, onObjectExistListener);
    }

    public void incrementWatchersCount(String postId) {
        DatabaseHelper databaseHelper = ApplicationHelper.getDatabaseHelper();
        databaseHelper.incrementWatchersCount(postId);
    }

    public void incrementNewPostsCounter() {
        newPostsCounter++;
        notifyPostCounterWatcher();
    }

    public void clearNewPostsCounter() {
        newPostsCounter = 0;
        notifyPostCounterWatcher();
    }

    public int getNewPostsCounter() {
        return newPostsCounter;
    }

    public void setPostCounterWatcher(PostCounterWatcher postCounterWatcher) {
        this.postCounterWatcher = postCounterWatcher;
    }

    private void notifyPostCounterWatcher() {
        if (postCounterWatcher != null) {
            postCounterWatcher.onPostCounterChanged(newPostsCounter);
        }
    }

    public interface PostCounterWatcher {
        void onPostCounterChanged(int newValue);
    }
}
