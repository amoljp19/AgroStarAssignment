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

import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.softaai.agrostarassigment.ApplicationHelper;
import com.softaai.agrostarassigment.enums.ProfileStatus;
import com.softaai.agrostarassigment.enums.UploadImagePrefix;
import com.softaai.agrostarassigment.managers.listeners.OnObjectChangedListener;
import com.softaai.agrostarassigment.managers.listeners.OnObjectExistListener;
import com.softaai.agrostarassigment.managers.listeners.OnProfileCreatedListener;
import com.softaai.agrostarassigment.model.Profile;
import com.softaai.agrostarassigment.utils.ImageUtil;
import com.softaai.agrostarassigment.utils.LogUtil;
import com.softaai.agrostarassigment.utils.PreferencesUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Kristina on 10/28/16.
 */

public class ProfileManager extends FirebaseListenersManager {

    private static final String TAG = ProfileManager.class.getSimpleName();
    private static ProfileManager instance;

    private Context context;
    private DatabaseHelper databaseHelper;
    private Map<Context, List<ValueEventListener>> activeListeners = new HashMap<>();


    public static ProfileManager getInstance(Context context) {
        if (instance == null) {
            instance = new ProfileManager(context);
        }

        return instance;
    }

    private ProfileManager(Context context) {
        this.context = context;
        databaseHelper = ApplicationHelper.getDatabaseHelper();
    }

    public Profile buildProfile(FirebaseUser firebaseUser, String largeAvatarURL) {
        Profile profile = new Profile(firebaseUser.getUid());
        profile.setEmail(firebaseUser.getEmail());
        profile.setUsername(firebaseUser.getDisplayName());
        profile.setPhotoUrl(largeAvatarURL != null ? largeAvatarURL : firebaseUser.getPhotoUrl().toString());
        return profile;
    }

    public void isProfileExist(String id, final OnObjectExistListener<Profile> onObjectExistListener) {
        DatabaseReference databaseReference = databaseHelper.getDatabaseReference().child("profiles").child(id);
        databaseReference.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                onObjectExistListener.onDataChanged(dataSnapshot.exists());
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    public void createOrUpdateProfile(Profile profile, OnProfileCreatedListener onProfileCreatedListener) {
        createOrUpdateProfile(profile, null, onProfileCreatedListener);
    }

    public void createOrUpdateProfile(final Profile profile, Uri imageUri, final OnProfileCreatedListener onProfileCreatedListener) {
        if (imageUri == null) {
            databaseHelper.createOrUpdateProfile(profile, onProfileCreatedListener);
            return;
        }

        String imageTitle = ImageUtil.generateImageTitle(UploadImagePrefix.PROFILE, profile.getId());

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

                        profile.setPhotoUrl(downloadUri.toString());
                        databaseHelper.createOrUpdateProfile(profile, onProfileCreatedListener);
                    } else {
                        onProfileCreatedListener.onProfileCreated(false);
                        LogUtil.logDebug(TAG, "fail to upload image");
                    }
                }
            });
        }
        else {
            onProfileCreatedListener.onProfileCreated(false);
            LogUtil.logDebug(TAG, "fail to upload image");
        }

//        UploadTask uploadTask = databaseHelper.uploadImage(imageUri, imageTitle);
//
//        if (uploadTask != null) {
//            uploadTask.addOnCompleteListener(new OnCompleteListener<UploadTask.TaskSnapshot>() {
//                @Override
//                public void onComplete(@NonNull Task<UploadTask.TaskSnapshot> task) {
//                    if (task.isSuccessful()) {
//                        Uri downloadUrl = task.getResult().getUploadSessionUri();
//                        LogUtil.logDebug(TAG, "successful upload image, image url: " + String.valueOf(downloadUrl));
//
//                        profile.setPhotoUrl(downloadUrl.toString());
//                        databaseHelper.createOrUpdateProfile(profile, onProfileCreatedListener);
//
//                    } else {
//                        onProfileCreatedListener.onProfileCreated(false);
//                        LogUtil.logDebug(TAG, "fail to upload image");
//                    }
//
//                }
//            });
//        } else {
//            onProfileCreatedListener.onProfileCreated(false);
//            LogUtil.logDebug(TAG, "fail to upload image");
//        }
    }

    public void getProfileValue(Context activityContext, String id, final OnObjectChangedListener<Profile> listener) {
        ValueEventListener valueEventListener = databaseHelper.getProfile(id, listener);
        addListenerToMap(activityContext, valueEventListener);
    }

    public void getProfileSingleValue(String id, final OnObjectChangedListener<Profile> listener) {
        databaseHelper.getProfileSingleValue(id, listener);
    }

    public ProfileStatus checkProfile() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user == null) {
            return ProfileStatus.NOT_AUTHORIZED;
        } else if (!PreferencesUtil.isProfileCreated(context)){
            return ProfileStatus.NO_PROFILE;
        } else {
            return ProfileStatus.PROFILE_CREATED;
        }
    }
}
